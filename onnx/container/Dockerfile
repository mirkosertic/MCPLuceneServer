FROM python:3.12-slim AS builder
LABEL org.opencontainers.image.source=https://github.com/mirkosertic/MCPLuceneServer
LABEL org.opencontainers.image.description="Container with quantized ONNX Models for embedding copmputation"
LABEL org.opencontainers.image.licenses=Apache-2.0
LABEL org.opencontainers.image.authors="Mirko Sertic<mirko.sertic@web.de>"

RUN pip3 install --upgrade pip
RUN pip3 install --upgrade --upgrade-strategy eager "optimum[onnx]"
RUN pip3 install sentence-transformers transformers torch
RUN pip3 install onnx onnxruntime

COPY e5-base-step1.sh /root
RUN /root/e5-base-step1.sh
COPY e5-base-step2.sh /root
RUN /root/e5-base-step2.sh
COPY e5-base-step3.sh /root
RUN /root/e5-base-step3.sh
COPY e5-base-step4.sh /root
RUN /root/e5-base-step4.sh
COPY e5-large-step1.sh /root
RUN /root/e5-large-step1.sh
COPY e5-large-step2.sh /root
RUN /root/e5-large-step2.sh
COPY e5-large-step3.sh /root
RUN /root/e5-large-step3.sh
COPY e5-large-step4.sh /root
RUN /root/e5-large-step4.sh

FROM scratch
COPY --from=builder /opt/onnxmodels/e5-base/model-final /onnxmodels/e5-base
COPY --from=builder /opt/onnxmodels/e5-large/model-final /onnxmodels/e5-large


