# proxyprotocolplugin

A Minecraft plugin that preserves players' real IP addresses when connecting through **NoobTunnel**.

**Tested Minecraft versions:** 1.8, 1.9, 1.10, 1.16, 26.3  
**Supported protocols:** PROXY Protocol v1 and v2

[Create your tunnel now](https://tunnel.byenoob.com)

Server-list refreshes (modern STATUS handshakes) from trusted peers work without a PROXY header. These connections retain the proxy's address and allow only a status request and ping. Joining the server still requires a PROXY v1/v2 header, and untrusted peers are still rejected.
