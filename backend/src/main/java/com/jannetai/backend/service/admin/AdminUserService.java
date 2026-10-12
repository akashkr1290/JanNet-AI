package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.AdminCreateUserRequest;
import com.jannetai.backend.dto.admin.AdminCreateUserResponse;
import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.DuplicateAccountException;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.GeoRepository;
import com.jannetai.backend.service.AuthService;
import com.jannetai.backend.service.notification.DeliveryOutcome;
import com.jannetai.backend.service.notification.EmailGatewayClient;
import com.jannetai.backend.service.notification.NotificationDeliveryException;
import com.jannetai.backend.service.notification.PiiMask;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.11 "Admin Module" / 16.3
 * "User & Role Management" screen). Staff account provisioning was
 * explicitly deferred here from Phase 13's PHASE_HANDOFF.md carry-forward
 * note.
 *
 * ROLE-PRIVILEGE MATRIX (see {@link #requireManageableRole}):
 * <ul>
 *   <li>CITIZEN and SUPER_ADMIN accounts are never touchable through this
 *   endpoint. CITIZEN is self-registration only (AuthService#register);
 *   SUPER_ADMIN is bootstrap-only (SuperAdminBootstrap, Phase 4) - there
 *   is deliberately no path to create a second Super Admin through the
 *   application layer.</li>
 *   <li>SRS 15.11 Business Rules: "only Super Administrator may create or
 *   modify Admin accounts". Read literally as covering every mutating
 *   action here (create with role=ADMIN, role change to/from ADMIN,
 *   status change, password reset, session revocation on an existing
 *   ADMIN account) - an ordinary ADMIN actor can manage
 *   GOVERNMENT_OFFICER/DEPARTMENT_HEAD/VERIFICATION_TEAM
 *   accounts only.</li>
 * </ul>
 *
 * TEMPORARY PASSWORD DESIGN (see {@link #createStaff} and
 * {@link #triggerPasswordReset}): a random temporary password is generated,
 * only its BCrypt hash is stored, and the password itself is e-mailed to the
 * staff member's own address through {@link EmailGatewayClient}. It is never
 * returned in an API response or written to a log. Previously the create
 * response carried the password for the Admin to copy from a one-time dialog
 * (lost for good if that dialog was dismissed), and "Reset Password" only sent
 * a reset OTP by SMS - which, with SMS disabled, reached nobody while the UI
 * reported success. An account therefore needs an e-mail address, and e-mail
 * delivery must be enabled; otherwise the request is refused with a clear
 * message and nothing is changed. If the mail server rejects the message the
 * transaction is rolled back, so no account is left with a password nobody
 * received.
 * The account is marked mobile-verified immediately (Admin-provisioned
 * accounts are trusted, unlike self-registration's OTP verification
 * loop).
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final WardRepository wardRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AuthService authService;
    private final EmailGatewayClient emailGatewayClient;
    private final GeoRepository geoRepository; // V34: staff area checks

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> list(Role role, UserStatus status, Long departmentId, String search,
                                           int page, int pageSize) {
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100));
        return userRepository.searchAdminUsers(role, status, departmentId, normalizedSearch, pageable)
                .map(UserProfileResponse::from);
    }

    @Transactional
    public AdminCreateUserResponse createStaff(User actor, AdminCreateUserRequest request) {
        requireManageableRole(actor, request.role());
        requireEmailDelivery(request.email(), "The new account");

        if (userRepository.existsByMobileNumber(request.mobileNumber())) {
            throw new DuplicateAccountException("Mobile number is already registered");
        }
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateAccountException("Email is already registered");
        }

        Department department = null;
        if (request.departmentId() != null) {
            department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Department not found: " + request.departmentId()));
        }
        Ward ward = null;
        if (request.wardId() != null) {
            ward = wardRepository.findById(request.wardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + request.wardId()));
        }
        Integer[] area = staffArea(request.districtCode(), request.localBodyCode(), ward, department);

        String temporaryPassword = generateTemporaryPassword();
        User user = User.builder()
                .fullName(request.fullName())
                .mobileNumber(request.mobileNumber())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(request.role())
                .department(department)
                .ward(ward)
                .districtCode(area[0])
                .localBodyCode(area[1])
                .reputationScore(100) // matches V3's DEFAULT 100, same as self-registration
                .status(UserStatus.ACTIVE)
                .failedLoginCount(0)
                .mobileVerifiedAt(LocalDateTime.now()) // Admin-provisioned - trusted, no OTP loop needed
                .build();
        User saved = userRepository.save(user);

        auditService.record(actor, "ADMIN_USER_CREATED", "USER", saved.getUserId(),
                AuditJson.of("role", request.role().name(), "district_code", area[0], "local_body_code", area[1],
                        "ward_id", ward != null ? ward.getWardId() : null));

        deliverTemporaryPassword(saved, temporaryPassword, false);
        return new AdminCreateUserResponse(UserProfileResponse.from(saved), PiiMask.email(saved.getEmail()));
    }

    /**
     * V34: {district, body} of a staff account, consistent with the chosen
     * ward and department. A ward of a body sets the body; a body sets the
     * district; an area-scoped department must cover the staff member's area.
     * The app fills these from the same location picker the citizen uses
     * (flutter .../admin/staff_area_picker.dart), so a mismatch is a 400.
     */
    Integer[] staffArea(Integer districtCode, Integer localBodyCode, Ward ward, Department department) {
        Integer body = localBodyCode;
        if (ward != null && ward.getLocalBodyCode() != null) {
            if (body != null && !body.equals(ward.getLocalBodyCode())) {
                throw new IllegalArgumentException("Ward " + ward.getName() + " is not part of the chosen local body");
            }
            body = ward.getLocalBodyCode();
        }
        Integer district = districtCode;
        if (body != null) {
            Integer finalBody = body;
            var ref = geoRepository.localBody(body)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown local body " + finalBody));
            if (district != null && !district.equals(ref.districtCode())) {
                throw new IllegalArgumentException(ref.name() + " is not in the chosen district");
            }
            district = ref.districtCode();
        } else if (district != null && geoRepository.district(district).isEmpty()) {
            throw new IllegalArgumentException("Unknown district " + district);
        }
        if (department != null) {
            if (department.getLocalBodyCode() != null && !department.getLocalBodyCode().equals(body)) {
                throw new IllegalArgumentException("Department " + department.getName()
                        + " serves another local body - choose a place inside its area");
            }
            if (department.getDistrictCode() != null && !department.getDistrictCode().equals(district)) {
                throw new IllegalArgumentException("Department " + department.getName()
                        + " serves another district - choose a place inside its area");
            }
        }
        return new Integer[]{district, body};
    }

    @Transactional
    public UserProfileResponse updateRole(User actor, Long targetUserId, Role newRole) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole()); // guards against touching an existing ADMIN account
        requireManageableRole(actor, newRole);           // guards against granting ADMIN without being SUPER_ADMIN

        Role before = target.getRole();
        target.setRole(newRole);
        User saved = userRepository.save(target);

        auditService.record(actor, "ADMIN_USER_ROLE_CHANGED", "USER", saved.getUserId(),
                "{\"before\":\"" + before + "\",\"after\":\"" + newRole + "\"}");

        return UserProfileResponse.from(saved);
    }

    /** V34: staff transfer to another area (district / local body / ward), audited with the previous area. */
    @Transactional
    public UserProfileResponse updateArea(User actor, Long targetUserId,
                                          com.jannetai.backend.dto.admin.AdminUpdateAreaRequest request) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole());
        Ward ward = null;
        if (request.wardId() != null) {
            ward = wardRepository.findById(request.wardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + request.wardId()));
        }
        Integer[] area = staffArea(request.districtCode(), request.localBodyCode(), ward, target.getDepartment());
        String before = AuditJson.of("district_code", target.getDistrictCode(), "local_body_code", target.getLocalBodyCode(),
                "ward_id", target.getWard() != null ? target.getWard().getWardId() : null);
        target.setDistrictCode(area[0]);
        target.setLocalBodyCode(area[1]);
        target.setWard(ward);
        User saved = userRepository.save(target);
        auditService.record(actor, "ADMIN_USER_AREA_CHANGED", "USER", saved.getUserId(), AuditJson.of(
                "before", before, "district_code", area[0], "local_body_code", area[1],
                "ward_id", ward != null ? ward.getWardId() : null));
        return UserProfileResponse.from(saved);
    }

    @Transactional
    public UserProfileResponse updateStatus(User actor, Long targetUserId, UserStatus newStatus) {
        User target = requireUser(targetUserId);
        if (target.getUserId().equals(actor.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot change your own account status");
        }
        requireManageableRole(actor, target.getRole());
        if (target.getErasedAt() != null && newStatus == UserStatus.ACTIVE) {
            // Audit GAP-041: an erased account has no personal data left and must stay closed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account's personal data was erased; it cannot be reactivated");
        }

        UserStatus before = target.getStatus();
        target.setStatus(newStatus);
        User saved = userRepository.save(target);

        // A suspension should take effect immediately, not at the target's
        // current access token's natural expiry - same reasoning as
        // AuthService#resetPassword's "old password no longer trusted" call
        // to revokeAllForUser.
        if (newStatus == UserStatus.SUSPENDED) {
            authService.logoutAllSessions(saved.getUserId());
        }

        auditService.record(actor, "ADMIN_USER_STATUS_CHANGED", "USER", saved.getUserId(),
                "{\"before\":\"" + before + "\",\"after\":\"" + newStatus + "\"}");

        return UserProfileResponse.from(saved);
    }

    /**
     * Sets a new random temporary password and e-mails it to the account's own
     * address (see class Javadoc's "TEMPORARY PASSWORD DESIGN"). The old
     * password stops working, a lockout is cleared and every session is
     * revoked, as for a self-service reset (AuthService#resetPassword).
     *
     * @return the masked address the password was sent to
     */
    @Transactional
    public String triggerPasswordReset(User actor, Long targetUserId) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole());
        requireEmailDelivery(target.getEmail(), "This account");

        String temporaryPassword = generateTemporaryPassword();
        target.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        target.setFailedLoginCount(0);
        target.setLockedUntil(null);
        User saved = userRepository.save(target);
        authService.logoutAllSessions(saved.getUserId());

        auditService.record(actor, "ADMIN_PASSWORD_RESET_TRIGGERED", "USER", saved.getUserId(), null);

        deliverTemporaryPassword(saved, temporaryPassword, true);
        return PiiMask.email(saved.getEmail());
    }

    @Transactional
    public void revokeSessions(User actor, Long targetUserId) {
        User target = requireUser(targetUserId);
        requireManageableRole(actor, target.getRole());
        authService.logoutAllSessions(target.getUserId());
        auditService.record(actor, "ADMIN_USER_SESSIONS_REVOKED", "USER", target.getUserId(), null);
    }

    // ---- helpers ----

    /** Refuses up front (nothing written) when the password could not be delivered. */
    private void requireEmailDelivery(String email, String subject) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, subject
                    + " has no e-mail address. The temporary password is delivered by e-mail, so an e-mail address is required.");
        }
        if (!emailGatewayClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "E-mail delivery is not configured, so the temporary password cannot be delivered. Nothing was changed.");
        }
    }

    /**
     * Sends the temporary password to the account's own address. Any failure
     * throws, rolling back the caller's transaction (see class Javadoc). The
     * password appears only in the message body - never in a log line.
     */
    private void deliverTemporaryPassword(User user, String temporaryPassword, boolean reset) {
        String subject = reset ? "JanNet AI - your password has been reset" : "JanNet AI - your staff account";
        String intro = reset
                ? "An administrator has reset the password of your JanNet AI staff account."
                : "An administrator has created a JanNet AI staff account for you (role: " + user.getRole() + ").";
        String body = "Hello " + user.getFullName() + ",\n\n"
                + intro + "\n\n"
                + "Sign in with your mobile number " + user.getMobileNumber() + " and this temporary password:\n\n"
                + "    " + temporaryPassword + "\n\n"
                + "You can choose your own password at any time with \"Forgot password\" on the sign-in screen.\n"
                + "Do not share this password with anyone. If you did not expect this e-mail, contact your administrator.\n\n"
                + "- JanNet AI";
        DeliveryOutcome outcome;
        try {
            outcome = emailGatewayClient.send(user.getEmail(), subject, body);
        } catch (NotificationDeliveryException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The temporary password e-mail could not be sent, so nothing was changed. Please try again.", e);
        }
        if (outcome != DeliveryOutcome.SENT) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The temporary password e-mail could not be sent, so nothing was changed. Please try again.");
        }
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    /** See class Javadoc's "ROLE-PRIVILEGE MATRIX". */
    private void requireManageableRole(User actor, Role role) {
        if (role == Role.CITIZEN || role == Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "CITIZEN and SUPER_ADMIN accounts cannot be managed through this endpoint");
        }
        if (role == Role.ADMIN && actor.getRole() != Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only Super Administrator may create or modify Admin accounts");
        }
    }

    /**
     * Generates a random password satisfying RegisterRequest's own policy
     * pattern (min 8 chars, upper, lower, digit, special) - reused here
     * rather than duplicated so a staff account's temporary password is
     * held to exactly the same strength bar as a citizen's self-chosen
     * one. Ambiguous-looking characters (0/O, 1/l/I) are excluded so an
     * Admin reading it aloud/typing it out for a new staff member doesn't
     * introduce a transcription error.
     */
    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;

        List<Character> chars = new ArrayList<>();
        chars.add(upper.charAt(random.nextInt(upper.length())));
        chars.add(lower.charAt(random.nextInt(lower.length())));
        chars.add(digits.charAt(random.nextInt(digits.length())));
        chars.add(special.charAt(random.nextInt(special.length())));
        for (int i = 0; i < 8; i++) {
            chars.add(all.charAt(random.nextInt(all.length())));
        }
        Collections.shuffle(chars, random);

        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }
}
