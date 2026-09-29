# The architecture is deliberately fixed to the same platform as the CircleCI VM.
# Package signatures remain checked; only expiry is disabled for the immutable archive snapshot.
FROM debian@sha256:7792b1f7702a86946cd518db72b6a407302c3e9bc1635634368b878189e8221c AS tools
ENV DEBIAN_FRONTEND=noninteractive
COPY debian.sources /etc/apt/sources.list.d/debian.sources
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-dkim openssl gnupg \
    && rm -rf /var/lib/apt/lists/*
COPY capture.py verify_content.py /opt/conformance/

FROM tools AS postfix
RUN apt-get update && apt-get install -y --no-install-recommends postfix=3.10.13-0+deb13u1 sasl2-bin libsasl2-modules \
    && rm -rf /var/lib/apt/lists/*
COPY postfix/ /etc/postfix/
COPY start_server.py /opt/conformance/
ENTRYPOINT ["python3", "/opt/conformance/start_server.py", "postfix"]

FROM tools AS exim
RUN apt-get update && apt-get install -y --no-install-recommends exim4-daemon-heavy=4.98.2-1+deb13u5 \
    && rm -rf /var/lib/apt/lists/*
COPY exim.conf /etc/exim4/exim4.conf
COPY start_server.py /opt/conformance/
ENTRYPOINT ["python3", "/opt/conformance/start_server.py", "exim"]
