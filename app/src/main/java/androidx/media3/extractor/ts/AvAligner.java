package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StreamLink: keeps audio and video in step inside one MPEG-TS chunk.
 *
 * <p>Some relayed streams stamp audio and video with clocks several seconds apart. Media3 lines
 * everything up by whichever timestamp it meets first, so the other track lands seconds away and
 * the player waits forever for it. Browsers line the tracks up separately. This measures where
 * each track starts and, if they're more than {@link #THRESHOLD_US} apart, moves the later-found
 * track onto the first one, so they start (and stay) together.
 */
@UnstableApi
public final class AvAligner {

  private static final long THRESHOLD_US = 1_500_000;
  private static final long MAX_US = 120_000_000;
  private static final AtomicInteger chunksLogged = new AtomicInteger();

  private long firstUs = C.TIME_UNSET;
  private long videoStartUs = C.TIME_UNSET;
  private long audioStartUs = C.TIME_UNSET;
  private long videoOffsetUs;
  private long audioOffsetUs;
  private boolean logged;

  synchronized long adjust(boolean video, long timeUs) {
    if (timeUs == C.TIME_UNSET) {
      return timeUs;
    }
    if (video ? videoStartUs == C.TIME_UNSET : audioStartUs == C.TIME_UNSET) {
      long offset = 0;
      if (firstUs == C.TIME_UNSET) {
        firstUs = timeUs;
      } else {
        long diff = timeUs - firstUs;
        if (Math.abs(diff) > THRESHOLD_US && Math.abs(diff) < MAX_US) {
          offset = diff;
          com.streamlink.tv.Report.INSTANCE.logOnce(
              "av-align",
              String.format(
                  Locale.US,
                  "Re-aligned audio and video: %s was stamped %.1fs %s %s",
                  video ? "video" : "audio",
                  Math.abs(diff) / 1_000_000.0,
                  diff > 0 ? "after" : "before",
                  video ? "audio" : "video"));
        }
      }
      if (video) {
        videoStartUs = timeUs;
        videoOffsetUs = offset;
      } else {
        audioStartUs = timeUs;
        audioOffsetUs = offset;
      }
      maybeLog();
    }
    return timeUs - (video ? videoOffsetUs : audioOffsetUs);
  }

  /** For the report: where each track starts in the first few chunks. */
  private void maybeLog() {
    if (logged || videoStartUs == C.TIME_UNSET || audioStartUs == C.TIME_UNSET) {
      return;
    }
    logged = true;
    int n = chunksLogged.incrementAndGet();
    if (n <= 4) {
      com.streamlink.tv.Report.INSTANCE.log(
          String.format(
              Locale.US,
              "Chunk timing: video starts %.2fs, audio starts %.2fs (gap %.2fs)",
              videoStartUs / 1_000_000.0,
              audioStartUs / 1_000_000.0,
              (videoStartUs - audioStartUs) / 1_000_000.0));
    }
  }

  synchronized void reset() {
    firstUs = C.TIME_UNSET;
    videoStartUs = C.TIME_UNSET;
    audioStartUs = C.TIME_UNSET;
    videoOffsetUs = 0;
    audioOffsetUs = 0;
    logged = false;
  }

  /** Wraps a reader so its timestamps go through the aligner. */
  ElementaryStreamReader wrap(ElementaryStreamReader reader, boolean video) {
    return new ElementaryStreamReader() {
      @Override
      public void seek() {
        reset();
        reader.seek();
      }

      @Override
      public void createTracks(
          ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
        reader.createTracks(extractorOutput, idGenerator);
      }

      @Override
      public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
        reader.packetStarted(adjust(video, pesTimeUs), flags);
      }

      @Override
      public void consume(ParsableByteArray data) throws ParserException {
        reader.consume(data);
      }

      @Override
      public void packetFinished() {
        reader.packetFinished();
      }

      @Override
      public void endOfInputReached() {
        reader.endOfInputReached();
      }
    };
  }
}
