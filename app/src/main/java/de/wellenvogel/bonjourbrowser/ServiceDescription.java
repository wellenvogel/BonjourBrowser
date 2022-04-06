package de.wellenvogel.bonjourbrowser;

public class ServiceDescription {
    public String service;
    public String protocol;
    public boolean alwaysExt=false;
    public int icon;
    public ServiceDescription(String service, String protocol, boolean alwaysExt,int icon){
        this.service=service;
        this.protocol=protocol;
        this.alwaysExt=alwaysExt;
        this.icon=icon;
    }
}
