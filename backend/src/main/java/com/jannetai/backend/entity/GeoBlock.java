package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of an LGD development block (V34 geo_blocks) so a complaint's
 * Location and a block Department can show the block name. Lists go through
 * service/geo/GeoRepository, not this entity.
 */
@Entity
@Immutable
@Table(name = "geo_blocks")
@Getter
@NoArgsConstructor
public class GeoBlock {

    @Id
    @Column(name = "block_code", columnDefinition = "INT UNSIGNED")
    private Integer blockCode;

    @Column(name = "district_code", nullable = false, columnDefinition = "INT UNSIGNED")
    private Integer districtCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;
}
