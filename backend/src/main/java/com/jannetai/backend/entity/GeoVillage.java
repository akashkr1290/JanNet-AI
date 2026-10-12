package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Read-only view of an LGD revenue village (V34 geo_villages), for display on a complaint location. */
@Entity
@Immutable
@Table(name = "geo_villages")
@Getter
@NoArgsConstructor
public class GeoVillage {

    @Id
    @Column(name = "village_code", columnDefinition = "INT UNSIGNED")
    private Integer villageCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "district_code", nullable = false, columnDefinition = "INT UNSIGNED")
    private Integer districtCode;
}
