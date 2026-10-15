package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * V37 routing order (DepartmentAssignmentService#forArea): Gram Panchayat /
 * town department, then block department, then district department, then the
 * global department the routing rule names. Codes are LGD: Gaya 196, a Gaya
 * block 1129, Pakri Gram Panchayat 95600.
 */
@ExtendWith(MockitoExtension.class)
class BlockRoutingTest {

    @Mock private RoutingRuleRepository routingRuleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private DepartmentAssignmentService service;

    private final Department global = dept(1L, null, null, null);
    private final Department gaya = dept(2L, 196, null, null);
    private final Department block = dept(3L, 196, 1129, null);
    private final Department pakri = dept(4L, 196, null, 95600);

    private static Department dept(long id, Integer district, Integer block, Integer body) {
        return Department.builder().departmentId(id).name("Public Works").districtCode(district).blockCode(block)
                .localBodyCode(body).isActive(true).build();
    }

    private static Complaint at(Integer district, Integer block, Integer body) {
        Location l = Location.builder().districtCode(district).blockCode(block).localBodyCode(body).build();
        return Complaint.builder().complaintId(1L).location(l).build();
    }

    @BeforeEach
    void setUp() {
        service = new DepartmentAssignmentService(routingRuleRepository, departmentRepository,
                complaintRepository, statusHistoryRepository, auditService, notificationService);
        lenient().when(departmentRepository.findFirstByNameAndLocalBodyCodeAndIsActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(departmentRepository.findFirstByNameAndBlockCodeAndLocalBodyCodeIsNullAndIsActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(departmentRepository
                        .findFirstByNameAndDistrictCodeAndBlockCodeIsNullAndLocalBodyCodeIsNullAndIsActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
    }

    @Test
    void blockDepartmentWinsOverTheDistrictDepartment() {
        lenient().when(departmentRepository.findFirstByNameAndBlockCodeAndLocalBodyCodeIsNullAndIsActiveTrue("Public Works", 1129))
                .thenReturn(Optional.of(block));
        lenient().when(departmentRepository
                        .findFirstByNameAndDistrictCodeAndBlockCodeIsNullAndLocalBodyCodeIsNullAndIsActiveTrue("Public Works", 196))
                .thenReturn(Optional.of(gaya));

        assertThat(service.forArea(global, at(196, 1129, 95600))).isSameAs(block);
    }

    @Test
    void gramPanchayatDepartmentWinsOverTheBlockDepartment() {
        lenient().when(departmentRepository.findFirstByNameAndLocalBodyCodeAndIsActiveTrue("Public Works", 95600))
                .thenReturn(Optional.of(pakri));
        lenient().when(departmentRepository.findFirstByNameAndBlockCodeAndLocalBodyCodeIsNullAndIsActiveTrue("Public Works", 1129))
                .thenReturn(Optional.of(block));

        assertThat(service.forArea(global, at(196, 1129, 95600))).isSameAs(pakri);
    }

    @Test
    void withoutABlockDepartmentTheDistrictDepartmentIsUsed() {
        lenient().when(departmentRepository
                        .findFirstByNameAndDistrictCodeAndBlockCodeIsNullAndLocalBodyCodeIsNullAndIsActiveTrue("Public Works", 196))
                .thenReturn(Optional.of(gaya));

        assertThat(service.forArea(global, at(196, 1129, 95600))).isSameAs(gaya);
    }

    @Test
    void aTownHasNoBlockSoTheBlockIsNotLookedUp() {
        assertThat(service.forArea(global, at(196, null, 900001))).isSameAs(global);
        verify(departmentRepository, never()).findFirstByNameAndBlockCodeAndLocalBodyCodeIsNullAndIsActiveTrue(anyString(), anyInt());
    }

    @Test
    void noAreaDepartmentKeepsTheGlobalOne() {
        assertThat(service.forArea(global, at(196, 1129, null))).isSameAs(global);
    }

    @Test
    void historyTextNamesTheBlock() {
        assertThat(DepartmentAssignmentService.routedAreaText(at(196, 1129, 95600)))
                .isEqualTo(" in local body 95600 in block 1129");
        assertThat(DepartmentAssignmentService.routedAreaText(at(196, 1129, null))).isEqualTo(" in block 1129");
        assertThat(DepartmentAssignmentService.routedAreaText(at(196, null, null)))
                .isEqualTo(" (no local body known - global department)");
    }
}
