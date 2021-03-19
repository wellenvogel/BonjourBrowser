package de.wellenvogel.bonjourbrowser;

import java.net.URI;

public class Target {
        public String name;
        public String host;
        public URI uri;
        Target(){}
        Target(String name, String host, URI uri){
            this.name=name;
            this.host=host;
            this.uri=uri;
        }
}
