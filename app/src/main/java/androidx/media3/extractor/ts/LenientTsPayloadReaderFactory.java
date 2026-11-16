package androidx.media3.extractor.ts;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Collections;

/**
 * StreamLink: Media3's {@link DefaultTsPayloadReaderFactory}, except H.264 video is read with
 * {@link LenientH264Reader} so streams without keyframes can start. Everything else is delegated.
 */
@UnstableApi
public final class LenientTsPayloadReaderFactory implements TsPayloadReader.Factory {

  private final DefaultTsPayloadReaderFactory delegate;
  private final int flags;
  /** One per extractor (this factory is created per TS extractor). */
  private final AvAligner aligner = new AvAligner();

  public LenientTsPayloadReaderFactory(DefaultTsPayloadReaderFactory delegate, int flags) {
    this.delegate = delegate;
    this.flags = flags;
  }

  @Override
  public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
    return delegate.createInitialPayloadReaders();
  }

  @Override
  @Nullable
  public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
    if (streamType == TsExtractor.TS_STREAM_TYPE_H264
        && (flags & DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM) == 0) {
      return new PesReader(
          aligner.wrap(
              new LenientH264Reader(
                  new SeiReader(Collections.emptyList()),
                  (flags & DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES) != 0,
                  (flags & DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS) != 0),
              /* video= */ true));
    }
    boolean aac = (flags & DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM) == 0;
    if (aac && streamType == TsExtractor.TS_STREAM_TYPE_AAC_ADTS) {
      return new PesReader(
          aligner.wrap(
              new AdtsReader(false, esInfo.language, esInfo.getRoleFlags()), /* video= */ false));
    }
    if (aac && streamType == TsExtractor.TS_STREAM_TYPE_AAC_LATM) {
      return new PesReader(
          aligner.wrap(new LatmReader(esInfo.language, esInfo.getRoleFlags()), /* video= */ false));
    }
    return delegate.createPayloadReader(streamType, esInfo);
  }
}
