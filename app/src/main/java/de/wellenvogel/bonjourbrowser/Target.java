package de.wellenvogel.bonjourbrowser;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;

public class Target {
        public String name;
        public InetAddress host;
        public URI uri;
        public NetworkInterface intf;
        public ServiceDescription description;
        Target(){}
        Target(String name, InetAddress host, URI uri,ServiceDescription description){
            this.name=name;
            this.host=host;
            this.uri=uri;
            this.description=description;
        }
}
