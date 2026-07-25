package ai.edgez.androiddevtools;

final class Endpoint {
    final String host;
    final int port;

    Endpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    String display() {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }
}

