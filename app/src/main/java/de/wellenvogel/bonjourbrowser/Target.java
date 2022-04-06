package de.wellenvogel.bonjourbrowser;

import java.net.NetworkInterface;
import java.net.URI;

public class Target {
        public String name;
        public String host;
        public URI uri;
        public NetworkInterface intf;
        public ServiceDescription description;
        Target(){}
        Target(String name, String host, URI uri,ServiceDescription description){
            this.name=name;
            this.host=host;
            this.uri=uri;
            this.description=description;
        }
}
