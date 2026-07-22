package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

@Document(collection = "v_orga_ambit")
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgaAmbit {

    @Id
    private String id;

    @Indexed(unique = true)
    private String uuid;
    private String orga;

    @Indexed
    private String identificador;
    private String parent;

    @Indexed
    private String ambit;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getOrga() { return orga; }
    public void setOrga(String orga) { this.orga = orga; }

    public String getIdentificador() { return identificador; }
    public void setIdentificador(String identificador) { this.identificador = identificador; }

    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }

    public String getAmbit() { return ambit; }
    public void setAmbit(String ambit) { this.ambit = ambit; }
}
