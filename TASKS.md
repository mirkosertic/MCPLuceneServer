# TASKS

Use for implementation the `implement-review-loop`-agent (see CLAUDE.md).

---

## Bug Fixes
- [x] the entrypoint.sh file has a hardcoded list of active profiles. There is currently no way to
  configure the active profiles in a docker container. So please modify the Dockerfile and the
  entrypoint.sh to default the profile to "deployed", but make it possible to override the profile
  to allow enabling the "vectorsearch" profile. Please also make the
  onnx model configurable in Container mode, this can by defined thru a JVM System property as
  stated in README.md. Please finally update the README.md to make it clear
  hot to enable the vectorsearch profile and configure the model /nwhen using the Container image.

## New Features
- [x] Please create a tools.http File in the root of the project. It should contain examples for evey
  implemented MCP tool in this project for be invoke using the HTTP Streaming MCP API. Assume that
  the tools are available at localhost:9000. There are no security requirements for the tools. Creste
  variants as need if the tools api allows different parameters and usages. Document each tool call
  and its intention in the .http file.
- [ ] Fair Assesment: Lucene vs. PostgreSQL vs. MySQL for Fulltextsearch based on the current
  indexing pipeline documented in PIPELINE.md
- [ ] OpenNLP vs HuggingFace for Lemmatization, which one is better?
