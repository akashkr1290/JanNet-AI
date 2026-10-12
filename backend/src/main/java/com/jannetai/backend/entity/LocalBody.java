package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of a Gram Panchayat / urban body (V34 local_bodies) for
 * display on a complaint location, an officer or a department. Rows are
 * loaded from LGD by database/tools/lgd_import, never created by the app.
 */
@Entity
@Immutable
@Table(name = "local_bodies")
@Getter
@NoArgsConstructor
public class LocalBody {

    @Id
    @Column(name = "local_body_code", columnDefinition = "INT UNSIGNED")
    private Integer localBodyCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** RURAL or URBAN. */
    @Column(name = "area_kind", nullable = false, length = 10)
    private String areaKind;

    /** LGD type name, e.g. "Village Panchayat", "Municipal Corporation". */
    @Column(name = "body_type", nullable = false, length = 60)
    private String bodyType;

    @Column(name = "district_code", columnDefinition = "INT UNSIGNED")
    private Integer districtCode;
}
