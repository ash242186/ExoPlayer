/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.smoothstreaming;

import com.google.android.exoplayer.AdaptiveSourceEventListener;
import com.google.android.exoplayer.AdaptiveSourceEventListener.EventDispatcher;
import com.google.android.exoplayer.C;
import com.google.android.exoplayer.CompositeSequenceableLoader;
import com.google.android.exoplayer.Format;
import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SampleSourceProvider;
import com.google.android.exoplayer.SequenceableLoader;
import com.google.android.exoplayer.TrackGroup;
import com.google.android.exoplayer.TrackGroupArray;
import com.google.android.exoplayer.TrackSelection;
import com.google.android.exoplayer.TrackStream;
import com.google.android.exoplayer.chunk.ChunkTrackStream;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.FormatEvaluator.AdaptiveEvaluator;
import com.google.android.exoplayer.extractor.mp4.TrackEncryptionBox;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.ProtectionElement;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.StreamElement;
import com.google.android.exoplayer.upstream.Allocator;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSourceFactory;
import com.google.android.exoplayer.upstream.Loader;
import com.google.android.exoplayer.upstream.ParsingLoadable;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.Util;

import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Base64;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link SampleSource} for SmoothStreaming media. Also acts as a {@link SampleSourceProvider}
 * providing {@link SmoothStreamingSampleSource} instances.
 */
public final class SmoothStreamingSampleSource implements SampleSource, SampleSourceProvider,
    SequenceableLoader.Callback<ChunkTrackStream<SmoothStreamingChunkSource>>,
    Loader.Callback<ParsingLoadable<SmoothStreamingManifest>> {

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

  private static final int MINIMUM_MANIFEST_REFRESH_PERIOD_MS = 5000;
  private static final int INITIALIZATION_VECTOR_SIZE = 8;

  private final Uri manifestUri;
  private final DataSourceFactory dataSourceFactory;
  private final BandwidthMeter bandwidthMeter;
  private final int minLoadableRetryCount;
  private final EventDispatcher eventDispatcher;
  private final SmoothStreamingManifestParser manifestParser;

  private DataSource manifestDataSource;
  private Loader manifestLoader;
  private ChunkTrackStream<SmoothStreamingChunkSource>[] trackStreams;
  private CompositeSequenceableLoader sequenceableLoader;

  private long manifestLoadStartTimestamp;
  private SmoothStreamingManifest manifest;

  private Callback callback;
  private Allocator allocator;
  private Handler manifestRefreshHandler;
  private boolean prepared;
  private long durationUs;
  private TrackEncryptionBox[] trackEncryptionBoxes;
  private TrackGroupArray trackGroups;
  private int[] trackGroupElementIndices;

  public SmoothStreamingSampleSource(Uri manifestUri, DataSourceFactory dataSourceFactory,
      BandwidthMeter bandwidthMeter, Handler eventHandler,
      AdaptiveSourceEventListener eventListener) {
    this(manifestUri, dataSourceFactory, bandwidthMeter, DEFAULT_MIN_LOADABLE_RETRY_COUNT,
        eventHandler, eventListener);
  }

  public SmoothStreamingSampleSource(Uri manifestUri, DataSourceFactory dataSourceFactory,
      BandwidthMeter bandwidthMeter, int minLoadableRetryCount, Handler eventHandler,
      AdaptiveSourceEventListener eventListener) {
    this.manifestUri = Util.toLowerInvariant(manifestUri.getLastPathSegment()).equals("manifest")
        ? manifestUri : Uri.withAppendedPath(manifestUri, "Manifest");
    this.dataSourceFactory = dataSourceFactory;
    this.bandwidthMeter = bandwidthMeter;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventDispatcher = new EventDispatcher(eventHandler, eventListener);
    manifestParser = new SmoothStreamingManifestParser();
  }

  // SampleSourceProvider implementation.

  @Override
  public int getSourceCount() {
    return 1;
  }

  @Override
  public SampleSource createSource(int index) {
    Assertions.checkArgument(index == 0);
    return this;
  }

  // SampleSource implementation.

  @Override
  public void prepare(Callback callback, Allocator allocator, long positionUs) {
    this.callback = callback;
    this.allocator = allocator;
    trackStreams = newTrackStreamArray(0);
    sequenceableLoader = new CompositeSequenceableLoader(trackStreams);
    manifestDataSource = dataSourceFactory.createDataSource();
    manifestLoader = new Loader("Loader:Manifest");
    manifestRefreshHandler = new Handler();
    startLoadingManifest();
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    manifestLoader.maybeThrowError();
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public TrackStream[] selectTracks(List<TrackStream> oldStreams,
      List<TrackSelection> newSelections, long positionUs) {
    int newEnabledSourceCount = trackStreams.length + newSelections.size() - oldStreams.size();
    ChunkTrackStream<SmoothStreamingChunkSource>[] newTrackStreams =
        newTrackStreamArray(newEnabledSourceCount);
    int newEnabledSourceIndex = 0;

    // Iterate over currently enabled streams, either releasing them or adding them to the new list.
    for (ChunkTrackStream<SmoothStreamingChunkSource> trackStream : trackStreams) {
      if (oldStreams.contains(trackStream)) {
        trackStream.release();
      } else {
        newTrackStreams[newEnabledSourceIndex++] = trackStream;
      }
    }

    // Instantiate and return new streams.
    TrackStream[] streamsToReturn = new TrackStream[newSelections.size()];
    for (int i = 0; i < newSelections.size(); i++) {
      newTrackStreams[newEnabledSourceIndex] = buildTrackStream(newSelections.get(i), positionUs);
      streamsToReturn[i] = newTrackStreams[newEnabledSourceIndex];
      newEnabledSourceIndex++;
    }

    trackStreams = newTrackStreams;
    sequenceableLoader = new CompositeSequenceableLoader(trackStreams);
    return streamsToReturn;
  }

  @Override
  public boolean continueLoading(long positionUs) {
    return sequenceableLoader.continueLoading(positionUs);
  }

  @Override
  public long getNextLoadPositionUs() {
    return sequenceableLoader.getNextLoadPositionUs();
  }

  @Override
  public long readDiscontinuity() {
    return C.UNSET_TIME_US;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = Long.MAX_VALUE;
    for (ChunkTrackStream<SmoothStreamingChunkSource> trackStream : trackStreams) {
      long rendererBufferedPositionUs = trackStream.getBufferedPositionUs();
      if (rendererBufferedPositionUs != C.END_OF_SOURCE_US) {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.END_OF_SOURCE_US : bufferedPositionUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    for (ChunkTrackStream<SmoothStreamingChunkSource> trackStream : trackStreams) {
      trackStream.seekToUs(positionUs);
    }
    return positionUs;
  }

  @Override
  public void release() {
    manifestDataSource = null;
    if (manifestLoader != null) {
      manifestLoader.release();
      manifestLoader = null;
    }
    if (trackStreams != null) {
      for (ChunkTrackStream<SmoothStreamingChunkSource> trackStream : trackStreams) {
        trackStream.release();
      }
      trackStreams = null;
    }
    sequenceableLoader = null;
    manifestLoadStartTimestamp = 0;
    manifest = null;
    callback = null;
    allocator = null;
    if (manifestRefreshHandler != null) {
      manifestRefreshHandler.removeCallbacksAndMessages(null);
      manifestRefreshHandler = null;
    }
    prepared = false;
    durationUs = 0;
    trackEncryptionBoxes = null;
    trackGroups = null;
    trackGroupElementIndices = null;
  }

  // SequenceableLoader.Callback implementation

  @Override
  public void onContinueLoadingRequested(ChunkTrackStream<SmoothStreamingChunkSource> trackStream) {
    callback.onContinueLoadingRequested(this);
  }

  // Loader.Callback implementation

  @Override
  public void onLoadCompleted(ParsingLoadable<SmoothStreamingManifest> loadable,
      long elapsedRealtimeMs, long loadDurationMs) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
    manifest = loadable.getResult();
    manifestLoadStartTimestamp = elapsedRealtimeMs - loadDurationMs;
    if (!prepared) {
      durationUs = manifest.durationUs;
      buildTrackGroups(manifest);
      ProtectionElement protectionElement = manifest.protectionElement;
      if (protectionElement != null) {
        byte[] keyId = getProtectionElementKeyId(protectionElement.data);
        trackEncryptionBoxes = new TrackEncryptionBox[] {
            new TrackEncryptionBox(true, INITIALIZATION_VECTOR_SIZE, keyId)};
      }
      prepared = true;
      callback.onSourcePrepared(this);
    } else {
      for (ChunkTrackStream<SmoothStreamingChunkSource> trackStream : trackStreams) {
        trackStream.getChunkSource().updateManifest(manifest);
      }
      callback.onContinueLoadingRequested(this);
    }
    scheduleManifestRefresh();
  }

  @Override
  public void onLoadCanceled(ParsingLoadable<SmoothStreamingManifest> loadable,
      long elapsedRealtimeMs, long loadDurationMs, boolean released) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
  }

  @Override
  public int onLoadError(ParsingLoadable<SmoothStreamingManifest> loadable, long elapsedRealtimeMs,
      long loadDurationMs, IOException error) {
    boolean isFatal = error instanceof ParserException;
    eventDispatcher.loadError(loadable.dataSpec, loadable.type, elapsedRealtimeMs, loadDurationMs,
        loadable.bytesLoaded(), error, isFatal);
    return isFatal ? Loader.DONT_RETRY_FATAL : Loader.RETRY;
  }

  // Internal methods

  private void scheduleManifestRefresh() {
    if (!manifest.isLive) {
      return;
    }
    long nextLoadTimestamp = manifestLoadStartTimestamp + MINIMUM_MANIFEST_REFRESH_PERIOD_MS;
    long delayUntilNextLoad = Math.max(0, nextLoadTimestamp - SystemClock.elapsedRealtime());
    manifestRefreshHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        startLoadingManifest();
      }
    }, delayUntilNextLoad);
  }

  private void startLoadingManifest() {
    ParsingLoadable<SmoothStreamingManifest> loadable = new ParsingLoadable<>(manifestDataSource,
        manifestUri, C.DATA_TYPE_MANIFEST, manifestParser);
    long elapsedRealtimeMs = manifestLoader.startLoading(loadable, this, minLoadableRetryCount);
    eventDispatcher.loadStarted(loadable.dataSpec, loadable.type, elapsedRealtimeMs);
  }

  private void buildTrackGroups(SmoothStreamingManifest manifest) {
    int trackGroupCount = 0;
    trackGroupElementIndices = new int[manifest.streamElements.length];
    TrackGroup[] trackGroupArray = new TrackGroup[manifest.streamElements.length];
    for (int i = 0; i < manifest.streamElements.length; i++) {
      StreamElement streamElement = manifest.streamElements[i];
      int streamElementType = streamElement.type;
      Format[] formats = streamElement.formats;
      if (formats.length > 0 && (streamElementType == C.TRACK_TYPE_AUDIO
          || streamElementType == C.TRACK_TYPE_VIDEO || streamElementType == C.TRACK_TYPE_TEXT)) {
        trackGroupElementIndices[trackGroupCount] = i;
        boolean adaptive = streamElementType == C.TRACK_TYPE_VIDEO;
        trackGroupArray[trackGroupCount++] = new TrackGroup(adaptive, formats);
      }
    }
    if (trackGroupCount < trackGroupArray.length) {
      trackGroupElementIndices = Arrays.copyOf(trackGroupElementIndices, trackGroupCount);
      trackGroupArray = Arrays.copyOf(trackGroupArray, trackGroupCount);
    }
    trackGroups = new TrackGroupArray(trackGroupArray);
  }

  private ChunkTrackStream<SmoothStreamingChunkSource> buildTrackStream(TrackSelection selection,
      long positionUs) {
    int[] selectedTracks = selection.getTracks();
    FormatEvaluator adaptiveEvaluator = selectedTracks.length > 1
        ? new AdaptiveEvaluator(bandwidthMeter) : null;
    int streamElementIndex = trackGroupElementIndices[selection.group];
    StreamElement streamElement = manifest.streamElements[streamElementIndex];
    int streamElementType = streamElement.type;
    DataSource dataSource = dataSourceFactory.createDataSource(bandwidthMeter);
    SmoothStreamingChunkSource chunkSource = new SmoothStreamingChunkSource(manifestLoader,
        manifest, streamElementIndex, trackGroups.get(selection.group), selectedTracks, dataSource,
        adaptiveEvaluator, trackEncryptionBoxes);
    return new ChunkTrackStream<>(streamElementType, chunkSource, this, allocator, positionUs,
        minLoadableRetryCount, eventDispatcher);
  }

  @SuppressWarnings("unchecked")
  private static ChunkTrackStream<SmoothStreamingChunkSource>[] newTrackStreamArray(int length) {
    return new ChunkTrackStream[length];
  }

  private static byte[] getProtectionElementKeyId(byte[] initData) {
    StringBuilder initDataStringBuilder = new StringBuilder();
    for (int i = 0; i < initData.length; i += 2) {
      initDataStringBuilder.append((char) initData[i]);
    }
    String initDataString = initDataStringBuilder.toString();
    String keyIdString = initDataString.substring(
        initDataString.indexOf("<KID>") + 5, initDataString.indexOf("</KID>"));
    byte[] keyId = Base64.decode(keyIdString, Base64.DEFAULT);
    swap(keyId, 0, 3);
    swap(keyId, 1, 2);
    swap(keyId, 4, 5);
    swap(keyId, 6, 7);
    return keyId;
  }

  private static void swap(byte[] data, int firstPosition, int secondPosition) {
    byte temp = data[firstPosition];
    data[firstPosition] = data[secondPosition];
    data[secondPosition] = temp;
  }

}
