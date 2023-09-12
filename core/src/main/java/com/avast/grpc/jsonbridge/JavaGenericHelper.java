package com.avast.grpc.jsonbridge;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;

import static io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor;

@SuppressWarnings("unchecked")
public class JavaGenericHelper {
    private JavaGenericHelper() {
    }

    /*
     * It's problematic to call self-bounded generic method from Scala,
     *  in this case the attachHeaders method has this generic: <T extends AbstractStub<T>>
     */
    public static AbstractStub attachHeaders(AbstractStub stub, Metadata extraHeaders) {
        return stub.withInterceptors(newAttachHeadersInterceptor(extraHeaders));
    }
}
