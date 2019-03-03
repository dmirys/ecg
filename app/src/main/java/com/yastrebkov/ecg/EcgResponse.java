package com.yastrebkov.ecg;

import com.google.gson.annotations.SerializedName;

public class EcgResponse {
    @SerializedName("Body")
    public final Body body;

    public EcgResponse(Body body) {
        this.body = body;
    }

    public static class Body {
        @SerializedName("Samples")
        public final int samples[];
        public Body(int samples[]) {
            this.samples = samples;
        }
    }
}
