# proxyprotocolplugin

A Minecraft plugin that preserves players' real IP addresses when connecting through **NoobTunnel**.

**Tested Minecraft versions:** 1.8, 1.9, 1.10, 1.16, 26.3  
**Supported protocols:** PROXY Protocol v1 and v2

[Create your tunnel now](https://tunnel.byenoob.com)

To publish an update, update the version in `pom.xml` and `src/main/resources/plugin.yml`, commit, then push a matching version tag such as `v1.0.1`. The release workflow runs the tests and publishes `real-ip.jar` and `real-ip.jar.sha256` together on GitHub Releases. Use stable version tags; this workflow publishes them as the latest release.

The permanent [latest JAR download](https://github.com/hinoobers/proxyprotocolplugin/releases/latest/download/real-ip.jar) and [SHA-256 checksum](https://github.com/hinoobers/proxyprotocolplugin/releases/latest/download/real-ip.jar.sha256) follow each published release. These links become available after the first successful release; ordinary source pushes do not publish downloads.
