FROM eclipse-temurin:17-jdk@sha256:0613a19436dc8f745914b25235d43f3b0eddb8d432d19edce30ffaf2d2f95403 AS build

RUN apt-get update -y && \
    apt-get install -y git curl gnupg

RUN useradd -ms /bin/bash rsk
USER rsk

WORKDIR /home/rsk
COPY --chown=rsk:rsk . ./

RUN gpg --keyserver https://secchannel.rsk.co/SUPPORT.asc --recv-keys 1DC9157991323D23FD37BAA7A6DBEAC640C5A14B && \
    gpg --verify --output SHA256SUMS SHA256SUMS.asc && \
    sha256sum --check SHA256SUMS && \
    ./configure.sh && \
    ./gradlew --no-daemon clean build -x test && \
    file=rskj-core/src/main/resources/version.properties && \
    version_number=$(sed -n 's/^versionNumber=//p' "$file" | tr -d "\"'") && \
    modifier=$(sed -n 's/^modifier=//p' "$file" | tr -d "\"'") && \
    cp "rskj-core/build/libs/rskj-core-$version_number-$modifier-all.jar" rsk.jar

FROM eclipse-temurin:17-jre@sha256:38e0afc86a10bf4cadbf1586fb617b3a9a4d09c9a0be882e29ada4ed0895fc84
LABEL org.opencontainers.image.authors="ops@rootstocklabs.com"

RUN useradd -ms /sbin/nologin -d /var/lib/rsk rsk
USER rsk

WORKDIR /var/lib/rsk
COPY --from=build --chown=rsk:rsk /home/rsk/rsk.jar ./
COPY --from=build --chown=rsk:rsk /home/rsk/test/node-timedMine.conf /var/lib/rsk/test/node-timedMine.conf


ENV MINER_ID="1"
ENV DEFAULT_JVM_OPTS="-Xms3G"
ENV RSKJ_SYS_PROPS="-Drsk.conf.file=./test/node-timedMine.conf -DtxLoad.profile=cpu -DtxLoad.enabled=false -Dwire.simulatedDelay=1000 -Dminer.server.skipPowValidation=true -Drsk.conf.file=./test/node-timedMine.conf -Drpc.providers.web.cors=* -Drpc.providers.web.http.port=4444 -Drpc.providers.web.http.enabled=true -Drpc.providers.web.ws.enabled=true -Drpc.providers.web.ws.port=4445 -Ddatabase.dir=./test/local-regtest/database -Dtransaction.accountTxRateLimit.enabled=false -Dlogging.dir=test/local-regtest/ -Dlogging=DEBUG -Dminer.server.enabled=true -Dminer.client.timedMine=true -Dsync.peer.count=20 -Drpc.providers.web.http.bind_address=0.0.0.0 -Drpc.providers.web.http.hosts.0=localhost -Drpc.providers.web.http.hosts.1=127.0.0.1 -Drpc.providers.web.http.hosts.2=::1"
ENV RSKJ_LOG_PROPS="-Dlogging.stdout=INFO"
ENV RSKJ_CLASS=co.rsk.Start
ENV RSKJ_OPTS="--regtest"

ENTRYPOINT ["/bin/sh", "-c", "exec java $DEFAULT_JVM_OPTS $RSKJ_SYS_PROPS -Dminer.coinbase.secret=miner$MINER_ID -Dpeer.privateKey=AFF6A83FEFFF6FF0C9F6FFFE41F6FF10D9FFFF3F41FFCFBF41F6FF90DFFFFF9$MINER_ID -Dpeer.port=5050$MINER_ID -Dminer.client.medianBlockTime=\"16 seconds\" $RSKJ_LOG_PROPS -cp rsk.jar $RSKJ_CLASS $RSKJ_OPTS \"${@}\"", "--"]

