package com.avast.grpc.jsonbridge;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;

@SuppressWarnings("unchecked")
class JavaGenericHelper {
    private JavaGenericHelper() {
    }

    public static <T extends AbstractStub<T>> AbstractStub<T> asAbstractStub(Object o) {
        return (T)o;
    }

    public static AbstractStub attachHeaders(Object stub, Metadata extraHeaders) {
        return MetadataUtils.attachHeaders((AbstractStub) stub, extraHeaders);
    }
}
