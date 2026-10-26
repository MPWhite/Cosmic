#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" = Darwin ]; then
    source ./macos/common.sh
    use_java21
fi
mkdir -p .local/population-scale
./mvnw -q -DskipTests test-compile dependency:build-classpath \
    -Dmdep.outputFile=.local/population-scale/test-classpath.txt
cat > .local/population-scale/benchmark-log4j2.xml <<'XML'
<Configuration status="ERROR"><Appenders><Console name="err" target="SYSTEM_ERR"><PatternLayout pattern="%level %message%n"/></Console></Appenders><Loggers><Root level="WARN"><AppenderRef ref="err"/></Root></Loggers></Configuration>
XML
exec java -Xmx1024m -Dwz-path=wz \
    -Dlog4j.configurationFile=.local/population-scale/benchmark-log4j2.xml \
    -cp "target/test-classes:target/classes:$(cat .local/population-scale/test-classpath.txt)" \
    server.population.ResidentScaleBenchmark
