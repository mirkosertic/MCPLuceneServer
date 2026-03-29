#!/bin/bash
PROFILES="${SPRING_PROFILES_ACTIVE:-deployed}"
VECTOR_OPTS=""
if [ -n "$VECTOR_MODEL" ]; then
  VECTOR_OPTS="-Dvector.model=$VECTOR_MODEL"
fi
java $JAVA_OPTS -Duser.home=/userdata -Dmcp.transport=http -Dmcp.http.port=9000 -Dmcp.http.host=0.0.0.0 --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector -Dspring.profiles.active=$PROFILES $VECTOR_OPTS -jar /tmp/luceneserver-0.0.1-SNAPSHOT.jar
