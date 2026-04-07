FROM eclipse-temurin:25
LABEL org.opencontainers.image.source=https://github.com/mirkosertic/MCPLuceneServer
LABEL org.opencontainers.image.description="MCP Lucene Server is a Model Context Protocol (MCP) server that exposes Apache Lucene's full-text search capabilities through a conversational interface. It allows AI assistants (like Claude) to help users search, index, and manage document collections without requiring technical knowledge of Lucene or search engines. "
LABEL org.opencontainers.image.licenses=Apache-2.0
LABEL org.opencontainers.image.authors="Mirko Sertic<mirko.sertic@web.de>"
ENV JAVA_OPTS="-Xmx2g"
ENV SPRING_PROFILES_ACTIVE=deployed
# Optional: restrict exposed MCP tools (comma-separated tool names or group shorthands)
# LUCENE_TOOLS_INCLUDE=search,semantic   (default: * = all tools)
# LUCENE_TOOLS_EXCLUDE=admin             (default: empty)
# Optional: ONNX model — required for semantic search tools to activate
# VECTOR_MODEL=e5-base
COPY ./target/luceneserver-0.0.1-SNAPSHOT.jar /tmp
COPY ./entrypoint.sh /tmp
WORKDIR /tmp
EXPOSE 9000
VOLUME /userdata
ENTRYPOINT ["./entrypoint.sh"]