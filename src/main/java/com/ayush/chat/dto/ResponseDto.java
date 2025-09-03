package com.ayush.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Data;

import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;

@Data
@XmlRootElement(name="Response")
@JsonRootName("Response")
@JsonIgnoreProperties(ignoreUnknown=true)
public class ResponseDto implements Serializable {

    private String status;
    private Object data;
    private Object meta;
    private String message;

}
