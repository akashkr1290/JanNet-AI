package com.jannetai.backend.dto.ward;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * V34: a colony / mohalla / sector / village listed for a ward (from the
 * body's ward notification). Lets AreaResolver find an urban ward from
 * Google's colony name when the ward has no boundary.
 * {@code villageCode}: set when the locality is an LGD village.
 */
public record WardLocalityRequest(@NotBlank @Size(max = 150) String name, Integer villageCode) {
}
