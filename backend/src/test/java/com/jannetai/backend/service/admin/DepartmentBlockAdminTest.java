package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.department.DepartmentUpsertRequest;
import com.jannetai.backend.dto.geo.BlockRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.GeoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V37: Admin can give a department one development block (Admin -> Departments). */
@ExtendWith(MockitoExtension.class)
class DepartmentBlockAdminTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private AuditService auditService;
    @Mock private GeoRepository geo;

    private DepartmentAdminService departments;
    private final User admin = User.builder().userId(1L).role(Role.ADMIN).fullName("Admin").build();

    @BeforeEach
    void setUp() {
        departments = new DepartmentAdminService(departmentRepository, userRepository, routingRuleRepository, auditService, geo);
        ReflectionTestUtils.setField(departments, "fallbackDepartmentName", "General Triage");
        lenient().when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(geo.block(1129)).thenReturn(Optional.of(new BlockRef(1129, "Gurua", 196)));
    }

    @Test
    void blockDepartmentTakesItsDistrictFromTheBlock() {
        var created = departments.create(admin, new DepartmentUpsertRequest("Public Works", null, null, null, null, 1129));

        assertThat(created.blockCode()).isEqualTo(1129);
        assertThat(created.districtCode()).isEqualTo(196);
        assertThat(created.localBodyCode()).isNull();
        ArgumentCaptor<Department> saved = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(saved.capture());
        assertThat(saved.getValue().getBlockCode()).isEqualTo(1129);
    }

    @Test
    void blockFromAnotherDistrictIsRejected() {
        assertThatThrownBy(() -> departments.create(admin,
                new DepartmentUpsertRequest("Public Works", null, null, 145, null, 1129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in district 145");
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void blockAndGramPanchayatTogetherAreRejected() {
        assertThatThrownBy(() -> departments.create(admin,
                new DepartmentUpsertRequest("Public Works", null, null, null, 95600, 1129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void unknownBlockIsRejected() {
        when(geo.block(4242)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departments.create(admin,
                new DepartmentUpsertRequest("Public Works", null, null, null, null, 4242)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown block");
    }

    @Test
    void sameNameInTheSameBlockIsAConflict() {
        Department existing = Department.builder().departmentId(7L).name("Public Works").districtCode(196).blockCode(1129)
                .isActive(true).build();
        when(departmentRepository.findFirstByNameAndDistrictCodeAndBlockCodeAndLocalBodyCode("Public Works", 196, 1129, null))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> departments.create(admin,
                new DepartmentUpsertRequest("Public Works", null, null, null, null, 1129)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void gramPanchayatDepartmentStillWorksWithoutABlock() {
        when(geo.localBody(95600)).thenReturn(Optional.of(new LocalBodyRef(95600, "Pakri", "RURAL", "Village Panchayat", 196)));

        var created = departments.create(admin, new DepartmentUpsertRequest("Public Works", null, null, null, 95600));

        assertThat(created.localBodyCode()).isEqualTo(95600);
        assertThat(created.districtCode()).isEqualTo(196);
        assertThat(created.blockCode()).isNull();
    }
}
