package com.jannetai.backend.dto.assistant;

/**
 * A suggested next step shown as a chip under an assistant reply.
 *
 * <ul>
 *   <li>{@code ASK} - send {@code value} as the next message;</li>
 *   <li>{@code OPEN_COMPLAINT} - open complaint {@code value} (complaint id);</li>
 *   <li>{@code OPEN_MY_COMPLAINTS} - open the My Complaints tab;</li>
 *   <li>{@code START_REPORT} - open the Submit tab with {@code value} as the description
 *       (the citizen still takes the photo, confirms the place and presses Submit).</li>
 * </ul>
 */
public record AssistantAction(String type, String label, String value) {

    public static final String ASK = "ASK";
    public static final String OPEN_COMPLAINT = "OPEN_COMPLAINT";
    public static final String OPEN_MY_COMPLAINTS = "OPEN_MY_COMPLAINTS";
    public static final String START_REPORT = "START_REPORT";

    public static AssistantAction ask(String label) {
        return new AssistantAction(ASK, label, label);
    }
}
