package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Locale;

class BufferTranscoder extends Encoder {
    protected static final String TAG = "encapp.Buffer_transcoder";

    private native int JNIDownScaler(ByteBuffer[] inpBuffer, ByteBuffer[] outBuffer,
                                     int inp_fr_wd, int inp_fr_ht, int[] inp_fr_stride, int inp_pix_fmt,
                                     int out_fr_wd, int out_fr_ht, int[] out_fr_stride, int out_pix_fmt,
                                     String downscale_filter);

    public static native int x264Init(X264ConfigParams x264ConfigParamsInstance, int width, int height, String colourSpace, int bitDepth, byte[] headerArray);

    public static native int x264Encode(byte[] yuvByteArray, byte[] outputBuffer, int width, int height,
                                        String colourSpace, findIDR findIDRInstance);

    public static native void x264Close();

    static {
        try {
            System.loadLibrary("DownScaler");
            System.loadLibrary("x264");
            Log.d(TAG,"Loading lib is done");
        } catch (UnsatisfiedLinkError ex) {
            Log.e(TAG, "Failed to load x264 library: " + ex.getMessage());
        }

    }

    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    // Flag to dump decoded YUV
    boolean mDecodeDump = false;

    //Ittiam: Added for buffer encoding :begin
    boolean mEncoding = true;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    File file = null;
    OutputStream fo = null;

    /*Flag indicates dequeue decoded o/p from decoder */
    boolean decOutputExtractDone = false;
    /*Flag indicates queuing input for decoder*/
    boolean decInpSubmitDone = false;
    boolean encInpSubmitDone = false;
    boolean encOutputExtractDone = false;
    int currentLoop = 1;
    MediaFormat currentDecOutputFormat = null;
    MediaFormat currentEncOutputFormat = null;
    Dictionary<String, Object> latestFrameChanges = null;

    /*For indicating too many consecutive failures while submitting decoded
    frame to encoder*/
    int failures = 0;
    int inpBitstreamFrWidth = 0;
    int inpBitstreamFrHeight = 0;
    int[] inpPlaneStride = new int[3];
    ByteBuffer[] inpByteBuffArr = new ByteBuffer[3];
    int actualFrSize = 0;
    int downscaledFrWidth = 0;
    int downscaledFrHeight = 0;
    int[] downscalePlaneStride = new int[3];
    ByteBuffer[] downscaleByteBuffArr = new ByteBuffer[3];
    int downscaledFrSize = 0;
    int decPixelfmt = 0;
    int encPixelfmt = 0;
    boolean mediaTekChip = false;
    int framesSubmitedToEnc = 0;
    int framesWritten = 0;

    //downscale FilterName can be "bicubic" or "lanczos"
    //By default is "lanczos"
    String downscaleFilterName = null;
    //Ittiam: Added for buffer encoding :end

    public BufferTranscoder(Test test) {
        super(test);
        mStats = new Statistics("Buffer encoder", mTest);
    }

    public String start(OutputMultiplier multiplier) {
        return start();
    }

    public static class X264ConfigParams {
        String preset;
        String tune;
        boolean fastfirstpass;
        String wpredp;
        float crf;
        float crf_max;
        int cqp;
        int aq_mode;
        int variance;
        int autovariance;
        int autovariance_biased;
        float aq_strength;
        boolean psy;
        float psy_rd;
        int rc_lookahead;
        boolean weightb;
        int weightp;
        boolean ssim;
        boolean intra_refresh;
        boolean bluray_compat;
        int b_bias;
        int b_pyramid;
        boolean mixed_refs;
        boolean dct_8x8;
        boolean fast_pskip;
        boolean aud;
        boolean mbtree;
        String deblock;
        float cplxblur;
        String partitions;
        int direct_pred;
        int slice_max_size;
        String stats;
        int nal_hrd;
        int avcintra_class;
        int me_method;
        int motion_est;
        boolean forced_idr;
        int coder;
        int b_strategy;
        int chromaoffset;
        int sc_threshold;
        int noise_reduction;
        int threads;

        public X264ConfigParams(String preset, String tune, boolean fastfirstpass, String wpredp, float crf, float crf_max, int qp, int aq_mode, int variance, int autovariance, int autovariance_biased, float aq_strength, boolean psy, float psy_rd, int rc_lookahead, boolean weightb, int weightp, boolean ssim, boolean intra_refresh, boolean bluray_compat, int b_bias, int b_pyramid, boolean mixed_refs, boolean dct_8x8, boolean fast_pskip, boolean aud, boolean mbtree, String deblock, float cplxblur, String partitions, int direct_pred, int slice_max_size, String stats, int nal_hrd, int avcintra_class, int me_method, int motion_est, boolean forced_idr, int coder, int b_strategy, int chromaoffset, int sc_threshold, int noise_reduction, int threads) {
            this.preset = preset;
            this.tune = tune;
            this.fastfirstpass = fastfirstpass;
            this.wpredp = wpredp;
            this.crf = crf;
            this.crf_max = crf_max;
            this.cqp = cqp;
            this.aq_mode = aq_mode;
            this.variance = variance;
            this.autovariance = autovariance;
            this.autovariance_biased = autovariance_biased;
            this.aq_strength = aq_strength;
            this.psy = psy;
            this.psy_rd = psy_rd;
            this.rc_lookahead = rc_lookahead;
            this.weightb = weightb;
            this.weightp = weightp;
            this.ssim = ssim;
            this.intra_refresh = intra_refresh;
            this.bluray_compat = bluray_compat;
            this.b_bias = b_bias;
            this.b_pyramid = b_pyramid;
            this.mixed_refs = mixed_refs;
            this.dct_8x8 = dct_8x8;
            this.fast_pskip = fast_pskip;
            this.aud = aud;
            this.mbtree = mbtree;
            this.deblock = deblock;
            this.cplxblur = cplxblur;
            this.partitions = partitions;
            this.direct_pred = direct_pred;
            this.slice_max_size = slice_max_size;
            this.stats = stats;
            this.nal_hrd = nal_hrd;
            this.avcintra_class = avcintra_class;
            this.me_method = me_method;
            this.motion_est = motion_est;
            this.forced_idr = forced_idr;
            this.coder = coder;
            this.b_strategy = b_strategy;
            this.chromaoffset = chromaoffset;
            this.sc_threshold = sc_threshold;
            this.noise_reduction = noise_reduction;
            this.threads = threads;
        }
    }

    public static class findIDR {
        boolean checkIDR;

        public findIDR(boolean checkIDR) {
            this.checkIDR = checkIDR;
        }
    }

    public String start() {
        Log.d(TAG,"** Buffer transcoding - " + mTest.getCommon().getDescription());

        if(mEncoding) {
            if (mTest.hasRuntime())
                mRuntimeParams = mTest.getRuntime();
        }
        if (mTest.getInput().hasRealtime()) {
            mRealtime = mTest.getInput().getRealtime();
        }

        if (mTest.getConfigure().hasDecodeDump()) {
            mDecodeDump = mTest.getConfigure().getDecodeDump();
        }

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();

        downscaleFilterName = mTest.getConfigure().getDownscaleFilter();
        if(downscaleFilterName == null) {
            downscaleFilterName = "lanczos";
        }
        //Check for MediaTek chip, because for MediaTek chip we must configure colour format as 420p,
        //even if we configure flexible, we are seeing chroma corrupted bitstream
        updateMediaTekChipflag();

        Log.d(TAG, "Create extractor");
        mExtractor = new MediaExtractor();

        MediaFormat inputFormat = null;
        int trackNum = 0;
        try {
            mExtractor.setDataSource(mTest.getInput().getFilepath());
            int tracks = mExtractor.getTrackCount();
            for (int track = 0; track < tracks; track++) {
                inputFormat = mExtractor.getTrackFormat(track);
                if (inputFormat.containsKey(MediaFormat.KEY_MIME) &&
                        inputFormat.getString(MediaFormat.KEY_MIME).toLowerCase(Locale.US).contains("video")) {
                    trackNum = track;
                }
            }
            Log.d(TAG, "Select track");
            mExtractor.selectTrack(trackNum);
            inputFormat = mExtractor.getTrackFormat(trackNum);
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }

            Log.d(TAG, "Check parsed input format:");
            logMediaFormat(inputFormat);

            Log.d(TAG, "Create decoder)");
            if (mTest.getDecoderConfigure().hasCodec()) {
                Log.d(TAG, "Create decoder by name: " + mTest.getDecoderConfigure().getCodec());
                mDecoder = MediaCodec.createByCodecName(mTest.getDecoderConfigure().getCodec());
            } else {
                Log.d(TAG, "Create decoder by mime: " + inputFormat.getString(MediaFormat.KEY_MIME));
                mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                Log.d(TAG, "Will create " + mDecoder.getCodecInfo().getName());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoderIsHardwareAccelerated(mDecoder.getCodecInfo().isHardwareAccelerated());
            }

            mTest = TestDefinitionHelper.updateInputSettings(mTest, inputFormat);
            try {
                mTest = TestDefinitionHelper.updateBasicSettings(mTest);
            } catch (RuntimeException e) {
                Log.e(TAG, "Error: " + e.getMessage());
            }

            Log.d(TAG, "Configure: " + mDecoder.getName());
            //Setting decoder colour format
            if(mediaTekChip) {
                inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
                decPixelfmt = 19;
            } else{
                inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                decPixelfmt = 21;
            }
            mDecoder.configure(inputFormat, null, null, 0);
            Log.d(TAG, "MediaFormat (post-test)");
            logMediaFormat(mDecoder.getInputFormat());
            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoder(mDecoder.getCodecInfo().getCanonicalName());
            } else {
                mStats.setDecoder(mDecoder.getCodecInfo().getName());
            }
        } catch (IOException iox) {
            mExtractor.release();
            Log.e(TAG, "Failed to create decoder: " + iox.getMessage());
            return "Failed to create decoder";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create decoder";
        }

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            mReferenceFrameRate = (float) (inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        }
        if (mFrameRate <= 0) {
            mFrameRate = mReferenceFrameRate;
        }
        mKeepInterval = mReferenceFrameRate / mFrameRate;

        if (inputFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            inpBitstreamFrWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        }
        if (inputFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            inpBitstreamFrHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        }

        Size res = SizeUtils.parseXString(mTest.getConfigure().getResolution());
        if(res != null) {
        downscaledFrWidth = res.getWidth();
        downscaledFrHeight = res.getHeight();
        }
        if((downscaledFrWidth == 0) || (downscaledFrHeight == 0)) {
            downscaledFrWidth = inpBitstreamFrWidth;
            downscaledFrHeight = inpBitstreamFrHeight;
        }
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        downscaledFrSize = (int) (downscaledFrWidth * downscaledFrHeight * 1.5);

        actualFrSize = (int)(inpBitstreamFrWidth*inpBitstreamFrHeight*1.5);

        if(mEncoding && !("encoder.x264".equals(mTest.getConfigure().getCodec()))) {
            MediaFormat encMediaFormat = null;
            try {
                // Unless we have a mime, do lookup
                if (mTest.getConfigure().getMime().length() == 0) {
                    Log.d(TAG, "codec id: " + mTest.getConfigure().getCodec());
                    try {
                        mTest = setCodecNameAndIdentifier(mTest);
                    } catch (Exception e) {
                        return e.getMessage();
                    }
                    Log.d(TAG, "codec: " + mTest.getConfigure().getCodec() + " mime: " + mTest.getConfigure().getMime());
                }
                Log.d(TAG, "Create encoder by name: " + mTest.getConfigure().getCodec());
                mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());

                encMediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
                Log.d(TAG, "MediaFormat (mTest)");
                logMediaFormat(encMediaFormat);
                setConfigureParams(mTest, encMediaFormat);
                //Setting encoder colour format
                if(mediaTekChip) {
                    encMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
                    encPixelfmt = 19;
                } else {
                    encMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                    encPixelfmt = 21;
                }
                Log.d(TAG, "MediaFormat (configure)");
                logMediaFormat(encMediaFormat);
                Log.d(TAG, "Configure: " + mCodec.getName());
                mCodec.configure(
                        encMediaFormat,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                Log.d(TAG, "MediaFormat (post-mTest)");
                logMediaFormat(mCodec.getInputFormat());
                mStats.setEncoderMediaFormat(mCodec.getInputFormat());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mStats.setCodec(mCodec.getCanonicalName());
                } else {
                    mStats.setCodec(mCodec.getName());
                }
            } catch (IOException iox) {
                Log.e(TAG, "Failed to create codec: " + iox.getMessage());
                return "Failed to create codec";
            } catch (MediaCodec.CodecException cex) {
                Log.e(TAG, "Configure failed: " + cex.getMessage());
                return "Failed to create codec";
            } catch(Exception e) {
                Log.e(TAG, "Unsupported profile or bitrate mode: " + e.getMessage());
                return "Failed to configure parameters profile or bitrate mode";
            }

            Log.d(TAG, "Create muxer");
            //mMuxer = createMuxer(mCodec, mCodec.getOutputFormat(), true);
            mMuxer = createMuxer(mCodec, encMediaFormat, false);

            // This is needed.
            boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
            if (isVP) {
                mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
                mMuxer.start();
            }

            try {
                Log.d(TAG, "Start encoder");
                mCodec.start();
            } catch (Exception ex) {
                Log.e(TAG, "Start failed: " + ex.getMessage());
                return "Start encoding failed";
            }

        } //mEncoding

        try {
            Log.d(TAG, "Start decoder");
            mDecoder.start();
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start decoding failed";
        }

        //mInitDone = true;
        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        mStats.start();
        try {
            // start bufferTranscoding
            bufferTranscoding(trackNum);
        } catch (Exception e) {
            e.printStackTrace();
        }

        stopAllActivity();
        inpByteBuffArr = null;
        inpPlaneStride = null;
        downscaleByteBuffArr = null;
        downscalePlaneStride = null;
        return "";
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }

    void updateMediaTekChipflag() {
        //From codecList in device any encoder/ decoder contains "mtk", we are setting
        // mediaTekChip flag as true.
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        String codecName = null;
        for (MediaCodecInfo info : codecInfos) {
            String str = MediaCodecInfoHelper.toText(info, 1);
            if (str.toLowerCase(Locale.US).contains("video")) {
                codecName = info.getName();
                if(codecName.toLowerCase(Locale.US).contains(".mtk.")) {
                    mediaTekChip = true;
                    break;
                }
                if(info.isEncoder() || mediaTekChip ) {
                    break;
                }
            }
        }
    }

    public class MuxerResult {
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo bufferInfo;
        private final int videoTrackIndex;

        // Constructor to initialize the fields
        public MuxerResult(MediaMuxer muxer, MediaCodec.BufferInfo bufferInfo, int videoTrackIndex) {
            this.muxer = muxer;
            this.bufferInfo = bufferInfo;
            this.videoTrackIndex = videoTrackIndex;
        }

        // Getter for MediaMuxer
        public MediaMuxer getMuxer() {
            return muxer;
        }

        // Getter for MediaCodec.BufferInfo
        public MediaCodec.BufferInfo getBufferInfo() {
            return bufferInfo;
        }

        // Getter for videoTrackIndex
        public int getVideoTrackIndex() {
            return videoTrackIndex;
        }
    }

    public MuxerResult createMuxerX264(byte[] headerArray) throws IOException {
        int videoTrackIndex = -1;
        boolean muxerStarted = false;
        MediaCodec.BufferInfo bufferInfo = null;
        MediaMuxer muxer = null;
        bufferInfo = new MediaCodec.BufferInfo();
        muxer = new MediaMuxer(Environment.getExternalStorageDirectory().getPath() + "/" + mTest.getEncoderX264().getOutputFile(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, inpBitstreamFrWidth, inpBitstreamFrHeight);
        format.setInteger(MediaFormat.KEY_WIDTH, inpBitstreamFrWidth);
        format.setInteger(MediaFormat.KEY_HEIGHT, inpBitstreamFrHeight);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 800000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 50);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        byte[][] spsPps = extractSpsPps(headerArray);

        ByteBuffer sps = ByteBuffer.wrap(spsPps[0]);
        ByteBuffer pps = ByteBuffer.wrap(spsPps[1]);
        format.setByteBuffer("csd-0", sps);
        format.setByteBuffer("csd-1", pps);

        videoTrackIndex = muxer.addTrack(format);
        muxer.start();
        muxerStarted = true;

        return new MuxerResult(muxer, bufferInfo, videoTrackIndex);
    }

    void bufferTranscoding(int trackIndex) throws IOException {
        if (mDecodeDump) {
            String outputYUVName = getOutputFilename() + ".yuv";
            Log.d(TAG, "YUV Filename: "+ outputYUVName);
            file = new File(Environment.getExternalStorageDirectory() + "/" + File.separator + outputYUVName);
            file.delete();
            file.createNewFile();
            fo = new FileOutputStream(file);
        }

        int estimatedSize = 1024;
        byte[] headerArray = new byte[estimatedSize];
        MediaMuxer muxer = null;
        MediaCodec.BufferInfo bufferInfo = null;
        int videoTrackIndex = -1;

        Arrays.fill(headerArray, (byte) 0);

        String enable_x264_encoder = mTest.getConfigure().getCodec();
        if("encoder.x264".equals(enable_x264_encoder)) {
            int width, height;
            Size res = SizeUtils.parseXString(mTest.getConfigure().getResolution());
            if(res != null) {
                width = res.getWidth();
                height = res.getHeight();
            }

            String preset = mTest.getEncoderX264().getPreset();
            String colorSpace = mTest.getEncoderX264().getColorSpace();
            int bitDepth = mTest.getEncoderX264().getBitdepth();
            int threads = mTest.getEncoderX264().getThreads();

            String tune = "ssim";
            boolean fastfirstpass = false;
            String wpredp = "auto";
            float crf = 23.0f;
            float crf_max = 0.0f;
            int qp = 0;
            int aq_mode = 0;
            int variance = 0;
            int autovariance = 0;
            int autovariance_biased = 0;
            float aq_strength = 1.0f;
            boolean psy = true;
            float psy_rd = 1.0f;
            int rc_lookahead = 40;
            boolean weightb = true;
            int weightp = 2;
            boolean ssim = false;
            boolean intra_refresh = false;
            boolean bluray_compat = false;
            int b_bias = 0;
            int b_pyramid = 1;
            boolean mixed_refs = true;
            boolean dct_8x8 = true;
            boolean fast_pskip = true;
            boolean aud = false;
            boolean mbtree = true;
            String deblock = "0:0";
            float cplxblur = 20.0f;
            String partitions = "all";
            int direct_pred = 1;
            int slice_max_size = 0;
            String stats = "";
            int nal_hrd = 0;
            int avcintra_class = 0;
            int me_method = 1;
            int motion_est = 1;
            boolean forced_idr = false;
            int coder = 1;
            int b_strategy = 1;
            int chromaoffset = 0;
            int sc_threshold = 40;
            int noise_reduction = 0;

            X264ConfigParams x264ConfigParamsInstance = new X264ConfigParams(
                    preset, tune, fastfirstpass, wpredp, crf, crf_max, qp, aq_mode, variance, autovariance, autovariance_biased, aq_strength, psy, psy_rd, rc_lookahead,
                    weightb, weightp, ssim, intra_refresh, bluray_compat, b_bias, b_pyramid, mixed_refs, dct_8x8, fast_pskip, aud, mbtree, deblock, cplxblur, partitions,
                    direct_pred, slice_max_size, stats, nal_hrd, avcintra_class, me_method, motion_est, forced_idr, coder, b_strategy, chromaoffset, sc_threshold, noise_reduction, threads
            );

            int sizeOfHeader = x264Init(x264ConfigParamsInstance, inpBitstreamFrWidth, inpBitstreamFrHeight, colorSpace, bitDepth, headerArray);
            MuxerResult result = createMuxerX264(headerArray);
            muxer = result.getMuxer();
            bufferInfo = result.getBufferInfo();
            videoTrackIndex = result.getVideoTrackIndex();

            Log.d(TAG, "after init java");
        }

        if(!("encoder.x264".equals(mTest.getConfigure().getCodec()))) {
            currentDecOutputFormat = mDecoder.getOutputFormat();
            currentEncOutputFormat = mCodec.getOutputFormat();
        }
        mLastTime = SystemClock.elapsedRealtimeNanos() / 1000;
        while (!encOutputExtractDone) {
            if (mInFramesCount % 100 == 0 && MainActivity.isStable()) {
                Log.d(TAG, mTest.getCommon().getId() + " - " +
                        "frames: " + mFramesAdded +
                        " inframes: " + mInFramesCount +
                        " current_loop: " + currentLoop +
                        " current_time: " + mCurrentTimeSec);
            }
            // Feed more data to the decoder.
            if (!decInpSubmitDone) {
                submitFrameForDecoding(trackIndex);
            }
            if("encoder.x264".equals(mTest.getConfigure().getCodec())) {
                getDecodedFrameAndSubmitForEncoding(headerArray, muxer, bufferInfo, videoTrackIndex);
            } else {
                //Get decoded frames from output buffers & submit to encoder
                if (!decOutputExtractDone) {
                    getDecodedFrameAndSubmitForEncoding();
                }
                //Get encoded data and write to mp4 file
                if(!encOutputExtractDone) {
                    getEncodedFrame();
                }
            }
            if(encOutputExtractDone){
                Log.d(TAG, "encOutputExtractDone is true and getEncodedFrame() execution is over");
            }
        }
        if (muxer != null) {
            try {
                muxer.release();
            } catch (IllegalStateException ise) {
                Log.e(TAG, "Illegal state exception when trying to release the muxer");
            }
        }
        if (mDecodeDump) fo.close();

        //Log.d(TAG, "Decoding done, leaving decoded: " + mStats.getDecodedFrameCount());
    }

    void submitFrameForDecoding(int trackIndex) {
        int index;
        long presentationTimeUs = 0L;
        index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US);
        if (index >= 0) {
            ByteBuffer inputBuffer = mDecoder.getInputBuffer(index);
            // Read the sample data into the ByteBuffer.  This neither respects nor
            // updates inputBuffer's position, limit, etc.
            int chunkSize = mExtractor.readSampleData(inputBuffer, 0);
            int flags = 0;
            if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                decInpSubmitDone = true;
            }
            if (chunkSize < 0) {
                if (mYuvReader != null) {
                    mYuvReader.closeFile();
                }
                currentLoop++;

                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true) || mYuvReader == null) {
                    // Set EOS flag and call encoder
                    Log.d(TAG, "*******************************");
                    Log.d(TAG, "End of stream");

                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    // End of stream -- send empty frame with EOS flag set.
                    mDecoder.queueInputBuffer(index, 0, 0, 0L,
                            flags);
                    decInpSubmitDone = true;
                }

                if (!decInpSubmitDone) {
                    Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                    mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                    Log.d(TAG, "*** Loop ended start " + currentLoop + "***");
                }

            } else {
                if (mExtractor.getSampleTrackIndex() != trackIndex) {
                    Log.w(TAG, "WEIRD: got sample from track " +
                            mExtractor.getSampleTrackIndex() + ", expected " + trackIndex);
                }
                presentationTimeUs = mExtractor.getSampleTime();
                mCurrentTimeSec = info.presentationTimeUs / 1000000.0;
                mStats.startDecodingFrame(presentationTimeUs, chunkSize, flags);

                mDecoder.queueInputBuffer(index, 0, chunkSize,
                        presentationTimeUs, flags /*flags*/);
                Log.d(TAG, "submitted frame to dec : " + mInFramesCount);
                mInFramesCount++;
                mExtractor.advance();
            }

        } else {
            Log.d(TAG, "Input buffer not available");
        }
    }

    void getDecodedFrameAndSubmitForEncoding() throws IOException {
        int index;
        index = mDecoder.dequeueOutputBuffer(info, (long) mFrameTimeUsec);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // no output available yet
        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaFormat oformat = mDecoder.getOutputFormat();
                latestFrameChanges = mediaFormatComparison(currentDecOutputFormat, oformat);
                currentDecOutputFormat = oformat;
            }
            Log.d(TAG, "Media Format changed");
        } else if(index >= 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                decOutputExtractDone = true;
                Log.d(TAG, "decOutputExtractDone is set to true in 'MediaCodec.BUFFER_FLAG_END_OF_STREAM' ");
                submitFrameForEncoding(null, info);
                Log.d(TAG, "Output EOS");
                try {
                    mDecoder.releaseOutputBuffer(index, 0);
                } catch (IllegalStateException isx) {
                    Log.e(TAG, "Illegal state exception when trying to release output buffers");
                }
            } else {
                // Get the output buffer and wrap it in an Image object
                Image image = mDecoder.getOutputImage(index);
                if (image != null) {
                    // Access the planes
                    Image.Plane[] planes = image.getPlanes();
                    // Ensure planes.length == 3 for YUV format (Y, U, V planes)
                    if (planes.length == 3) {
                        FrameInfo frameInfo = mStats.stopDecodingFrame(info.presentationTimeUs);
                        frameInfo.addInfo(latestFrameChanges);
                        latestFrameChanges = null;
                        getYUVByteBuffers(planes, inpByteBuffArr, inpPlaneStride, mDecodeDump);
                    } else {
                        Log.e(TAG, "Not supported colour format");
                    }
                    submitFrameForEncoding(null, info);
                }
                try {
                    image.close();
                    mDecoder.releaseOutputBuffer(index, 0);
                } catch (IllegalStateException isx) {
                    Log.e(TAG, "Illegal state exception when trying to release output buffers");
                }
            }
        }
        if(mRealtime) sleepUntilNextFrame(mFrameTimeUsec);
    }

    public static byte[][] extractSpsPps(byte[] headerArray) {
        int spsStart = -1;
        int spsEnd = -1;
        int ppsStart = -1;
        int ppsEnd = -1;

        for (int i = 0; i < headerArray.length - 4; i++) {
            // Check for the start code 0x00000001 or 0x000001
            if ((headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x00 && headerArray[i+3] == 0x01) ||
                    (headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x01)) {

                int nalType = headerArray[i + (headerArray[i + 2] == 0x01 ? 3 : 4)] & 0x1F;
                if (nalType == 7 && spsStart == -1) { // SPS NAL unit type is 7
                    spsStart = i;
                } else if (nalType == 8 && spsStart != -1 && spsEnd == -1) { // PPS NAL unit type is 8
                    spsEnd = i;
                    ppsStart = i;
                }
                else if(spsEnd != -1 && ppsStart != -1) {
                    if(headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x01 && headerArray[i-1] != 0x00) {
                        ppsEnd = i;
                        break;
                    }
                }
            }
        }

        byte[] spsBuffer = Arrays.copyOfRange(headerArray, spsStart, spsEnd);
        byte[] ppsBuffer = Arrays.copyOfRange(headerArray, ppsStart, ppsEnd);

        return new byte[][]{spsBuffer, ppsBuffer};
    }

    public static byte[] convertPlanesToByteArray(Image.Plane[] planes) {
        // Extract byte data from each plane
        ByteBuffer plane0 = planes[0].getBuffer();
        byte[] byteArray0 = new byte[plane0.remaining()];
        plane0.get(byteArray0);

        ByteBuffer plane1 = planes[1].getBuffer();
        byte[] byteArray1 = new byte[plane1.remaining()];
        plane1.get(byteArray1);

        ByteBuffer plane2 = planes[2].getBuffer();
        byte[] byteArray2 = new byte[plane2.remaining()];
        plane2.get(byteArray2);

        int totalLength = byteArray0.length + byteArray1.length + byteArray2.length;

        byte[] concatenated = new byte[totalLength];
        int currentIndex = 0;

        System.arraycopy(byteArray0, 0, concatenated, currentIndex, byteArray0.length);
        currentIndex += byteArray0.length;

        System.arraycopy(byteArray1, 0, concatenated, currentIndex, byteArray1.length);
        currentIndex += byteArray1.length;

        System.arraycopy(byteArray2, 0, concatenated, currentIndex, byteArray2.length);

        return concatenated;
    }

    /*
    void getDecodedFrame() throws IOException {
        int index;
        index = mDecoder.dequeueOutputBuffer(info, (long) mFrameTimeUsec);

        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // no output available yet
        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaFormat oformat = mDecoder.getOutputFormat();
                latestFrameChanges = mediaFormatComparison(currentDecOutputFormat, oformat);
                currentDecOutputFormat = oformat;
            }
            Log.d(TAG, "Media Format changed");
        } else if(index >= 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                decOutputExtractDone = true;
                Log.d(TAG, "decOutputExtractDone is set to true in 'MediaCodec.BUFFER_FLAG_END_OF_STREAM' ");
                submitFrameForEncoding(null, info);
                Log.d(TAG, "Output EOS");
                try {
                    mDecoder.releaseOutputBuffer(index, 0);
                } catch (IllegalStateException isx) {
                    Log.e(TAG, "Illegal state exception when trying to release output buffers");
                }
            } else {
                // Get the output buffer and wrap it in an Image object
                Image image = mDecoder.getOutputImage(index);
                if (image != null) {
                    // Access the planes
                    Image.Plane[] planes = image.getPlanes();
                    // Ensure planes.length == 3 for YUV format (Y, U, V planes)
                    if (planes.length == 3) {
                        FrameInfo frameInfo = mStats.stopDecodingFrame(info.presentationTimeUs);
                        frameInfo.addInfo(latestFrameChanges);
                        latestFrameChanges = null;
                        getYUVByteBuffers(planes, inpByteBuffArr, inpPlaneStride, mDecodeDump);
                        byte[] yuvByteArray = convertPlanesToByteArray(planes);
                    } else {
                        Log.e(TAG, "Not supported colour format");
                    }
                }
            }
        }

        return yuvByteArray;
    }
    */

    void getDecodedFrameAndSubmitForEncoding(byte[] headerArray, MediaMuxer muxer,
                                             MediaCodec.BufferInfo bufferInfo, int videoTrackIndex) throws IOException {
        int index;
        boolean checkIDR = false;
        findIDR findIDRInstance = new findIDR(checkIDR);

        index = mDecoder.dequeueOutputBuffer(info, (long) mFrameTimeUsec);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // no output available yet
        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaFormat oformat = mDecoder.getOutputFormat();
                latestFrameChanges = mediaFormatComparison(currentDecOutputFormat, oformat);
                currentDecOutputFormat = oformat;
            }
            Log.d(TAG, "Media Format changed");
        } else if(index >= 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                decOutputExtractDone = true;
                Log.d(TAG, "decOutputExtractDone is set to true in 'MediaCodec.BUFFER_FLAG_END_OF_STREAM' ");
                submitFrameForEncoding(null, info);
                Log.d(TAG, "Output EOS");
                try {
                    mDecoder.releaseOutputBuffer(index, 0);
                } catch (IllegalStateException isx) {
                    Log.e(TAG, "Illegal state exception when trying to release output buffers");
                }
            } else {
                // Get the output buffer and wrap it in an Image object
                Image image = mDecoder.getOutputImage(index);
                if (image != null) {
                    // Access the planes
                    Image.Plane[] planes = image.getPlanes();
                    // Ensure planes.length == 3 for YUV format (Y, U, V planes)
                    if (planes.length == 3) {
                        FrameInfo frameInfo = mStats.stopDecodingFrame(info.presentationTimeUs);
                        frameInfo.addInfo(latestFrameChanges);
                        latestFrameChanges = null;
                        getYUVByteBuffers(planes, inpByteBuffArr, inpPlaneStride, mDecodeDump);
                    } else {
                        Log.e(TAG, "Not supported colour format");
                    }

                    if (encInpSubmitDone & (mInFramesCount==(framesWritten + 1))) {
                        encOutputExtractDone = true;
                    }

                    if ("encoder.x264".equals(mTest.getConfigure().getCodec())) {
                        int frameSize = inpBitstreamFrWidth * inpBitstreamFrHeight * 3 / 2;
                        int outputBufferSize;

                        byte[] outputBuffer = new byte[frameSize];

                        boolean flagHeaderSize = true;
                        byte[] yuvByteArray = convertPlanesToByteArray(planes);

                        String colourSpace = mTest.getEncoderX264().getColorSpace();
                        outputBufferSize = x264Encode(yuvByteArray, outputBuffer, inpBitstreamFrWidth, inpBitstreamFrHeight, colourSpace, findIDRInstance);
                        Log.d(TAG,"outputBufferSize: " + outputBufferSize);

                        ByteBuffer buffer = ByteBuffer.wrap(outputBuffer);
                        bufferInfo.offset = 0;
                        bufferInfo.size = outputBufferSize;
                        bufferInfo.presentationTimeUs = computePresentationTimeUsec(mFramesAdded, mRefFrameTime);
                        //if (findIDRInstance.checkIDR) {
                            bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        //} else {
                        //    bufferInfo.flags = 0;
                        //}

                        if(muxer != null) {
                            buffer.position(bufferInfo.offset);
                            buffer.limit(bufferInfo.offset + bufferInfo.size);

                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                            //if(flagHeaderSize)
                            //    fileOutputStream2.write(headerArray, 0, sizeOfHeader);
                            //fileOutputStream.write(buffer.array(), 0, outputBufferSize);
                        }
                        framesWritten++;
                        flagHeaderSize = false;
                        encInpSubmitDone = true;
                    }
                }
                try {
                    image.close();
                    mDecoder.releaseOutputBuffer(index, 0);
                } catch (IllegalStateException isx) {
                    Log.e(TAG, "Illegal state exception when trying to release output buffers");
                }
            }
        }
        if(mRealtime) sleepUntilNextFrame(mFrameTimeUsec);
    }

    void submitFrameForEncoding(ByteBuffer decodedBuffer, MediaCodec.BufferInfo decodedBufferInfo ) throws IOException {
        int index = -1;
        long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
        while (index < 0) {
            index = mCodec.dequeueInputBuffer(timeoutUs);
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG, "Waiting for input queue buffer for encoding");
                continue;
            } else if (index >=0) {
                Image  encInpimage = mCodec.getInputImage(index);
                if(encInpimage != null) {
                    if(!decOutputExtractDone) {
                        // Access the planes
                        Image.Plane[] planes = encInpimage.getPlanes();
                        if (planes.length == 3) {
                            getYUVByteBuffers(planes, downscaleByteBuffArr,downscalePlaneStride, false);
                        }else {
                            Log.e(TAG, "Not supported colour format");
                        }
                        int retValue = JNIDownScaler(inpByteBuffArr, downscaleByteBuffArr, inpBitstreamFrWidth, inpBitstreamFrHeight, inpPlaneStride, decPixelfmt,
                                downscaledFrWidth, downscaledFrHeight, downscalePlaneStride,encPixelfmt, downscaleFilterName);
                        if(retValue !=0) {
                            Log.d(TAG, "JNI retValue : " + retValue);
                        }

                        mStats.startEncodingFrame(decodedBufferInfo.presentationTimeUs, mInFramesCount);
                        // Queue the buffer for encoding
                        mCodec.queueInputBuffer(index, 0 /* offset */, downscaledFrSize,
                                decodedBufferInfo.presentationTimeUs /* timeUs */, decodedBufferInfo.flags);
                        //Log.d(TAG, "Flag: " + decodedBufferInfo.flags + " Size: " + decodedBufferInfo.size + " presentationTimeUs: "+decodedBufferInfo.presentationTimeUs +
                        //        " submitted frame to enc: " + mInFramesCount);
                        Log.d(TAG, "submitted frame to enc : " + framesSubmitedToEnc);
                        framesSubmitedToEnc++;
                    } else {
                        decodedBufferInfo.flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        mCodec.queueInputBuffer(index, 0 /* offset */, 0, decodedBufferInfo.presentationTimeUs /* timeUs */, decodedBufferInfo.flags);
                        synchronized (this) {
                            try {
                                wait(WAIT_TIME_SHORT_MS);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        encInpSubmitDone = true;
                        Log.d(TAG, "Submitted EOF for encoder ");
                        Log.d(TAG, "Flag: " + decodedBufferInfo.flags + " Size: " + decodedBufferInfo.size + " presentationTimeUs: "+decodedBufferInfo.presentationTimeUs +
                                " submitted frame for enc: " + mInFramesCount);
                    }
                }else {
                    Log.d(TAG, "encInpBuffer is null");
                }

            } else {
                Log.d(TAG, "index value: " + index);
            }
        }
    }

    void getEncodedFrame() {
        int index = 1;
        while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
            long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
            index = mCodec.dequeueOutputBuffer(info, timeoutUs);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // check if the input is already done
                if (encInpSubmitDone && (framesSubmitedToEnc==framesWritten)) {
                    encOutputExtractDone = true;
                    Log.d(TAG, "encOutputExtractDone is set to true in 'MediaCodec.INFO_TRY_AGAIN_LATER' ");
                }
                // otherwise ignore
            }
            if(index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (Build.VERSION.SDK_INT >= 29) {
                    MediaFormat oformat = mCodec.getOutputFormat();
                    latestFrameChanges = mediaFormatComparison(currentEncOutputFormat, oformat);
                    currentEncOutputFormat = oformat;
                }
            } else if (index >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    MediaFormat oformat = mCodec.getOutputFormat();

                    if (mWriteFile) {
                        mVideoTrack = mMuxer.addTrack(oformat);
                        mMuxer.start();
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encOutputExtractDone = true;
                    Log.d(TAG, "encOutputExtractDone is set to true in 'MediaCodec.BUFFER_FLAG_END_OF_STREAM' ");
                } else {
                    FrameInfo frameInfo = mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    //++mOutFramesCount;
                    frameInfo.addInfo(latestFrameChanges);
                    latestFrameChanges = null;
                    if (mMuxer != null && mVideoTrack != -1) {
                        ++mOutFramesCount;
                        ByteBuffer data = mCodec.getOutputBuffer(index);
                        mMuxer.writeSampleData(mVideoTrack, data, info);
                        //Log.d(TAG, "Muxer writing to file Frame No:: " + mOutFramesCount + " encoded o/p size: " +data.limit());
                        Log.d(TAG, "Muxer writing to file Frame No:: " + framesWritten);
                        framesWritten++;
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                    mCurrentTimeSec = info.presentationTimeUs / 1000000.0;
                }
            }
        }
    }

    void getYUVByteBuffers(Image.Plane[] planes, ByteBuffer[] tempPlanebuffs, int[] tempPlanestrides, boolean yuvDump) throws IOException {
        // Update  Y,U & V plane pointers and strides
        for(int i=0; i<planes.length;i++) {
            tempPlanebuffs[i] = planes[i].getBuffer();
            tempPlanestrides[i] = planes[i].getRowStride();
            if(yuvDump && (tempPlanebuffs[i].hasRemaining())) {
                int planeSize = tempPlanebuffs[i].remaining();
                // Access data from the buffers
                byte[] planeData = new byte[planeSize];
                tempPlanebuffs[i].get(planeData);
                if (file.exists()) {
                    fo.write(planeData);
                }
                planeData = null;
                tempPlanebuffs[i].rewind();
            }
        }
    }
    public void stopAllActivity(){
        int waitCount = 0;
        synchronized (this) {
            while (framesSubmitedToEnc > framesWritten) {
                Log.d(TAG, "Give me a sec, waiting for last encodings dec: " + framesSubmitedToEnc + " > enc: " + framesWritten);
                try {
                    wait(WAIT_TIME_SHORT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                waitCount++;
                // If already waited 10 times, just break
                if (waitCount == 10)
                    break;
            }
            mStats.stop();
            Log.d(TAG, "Submitted frames to enc: " + framesSubmitedToEnc + " extracted frames from enc " + framesWritten);
            try {
                if (mCodec != null) {
                    mCodec.flush();
                    mCodec.stop();
                    mCodec.release();
                }
                if (mDecoder != null) {
                    mDecoder.flush();
                    mDecoder.stop();
                    mDecoder.release();
                }
            } catch (IllegalStateException iex) {
                Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage());
            }

            Log.d(TAG, "Close muxer and streams");
            if (mMuxer != null) {
                try {
                    mMuxer.release(); //Release calls stop
                } catch (IllegalStateException ise) {
                    //Most likely mean that the muxer is already released. Stupid API
                    Log.e(TAG, "Illegal state exception when trying to release the muxer");
                }
            }

            if (mExtractor != null)
                mExtractor.release();
            Log.d(TAG, "Stop writer");
            mDataWriter.stopWriter();
        }
    }
    public void release() {
    }
}
