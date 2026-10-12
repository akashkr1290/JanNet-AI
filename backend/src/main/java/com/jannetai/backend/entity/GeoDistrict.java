package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of an LGD district (V34 geo_districts) so a complaint's
 * Location can show the district name (Location#getDistrict). Lists and
 * changes go through service/geo/GeoRepository, not this entity.
 */
@Entity
@Immutable
@Table(name = "geo_districts")
@Getter
@NoArgsConstructor
public class GeoDistrict {

    @Id
    @Column(name = "district_code", columnDefinition = "INT UNSIGNED")
    private Integer districtCode;

    @Column(name = "state_code", nullable = false, columnDefinition = "INT UNSIGNED")
    private Integer stateCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_served", nullable = false)
    private Boolean isServed;
}
