#!/usr/bin/env sh
# Spustenie aplikácie Autoservis (potrebná Java 17 alebo novšia)
# JAR vznikne príkazom "mvn clean package" v priečinku app (target/autoservis-pds.jar)
cd "$(dirname "$0")" || exit 1
JAR=target/autoservis-pds.jar
[ -f autoservis-pds.jar ] && JAR=autoservis-pds.jar
exec java -jar "$JAR"
