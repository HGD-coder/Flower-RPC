package com.github.hgdcoder;

import jdk.jfr.DataAmount;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Hello implements Serializable {
    private static final long serialVersionUID = 1L;

    private String message;
    private String description;
}
