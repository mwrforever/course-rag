package com.commerce.rag.bot.tool.dto;

/**
 * Simplified enrollment info returned by {@code queryEnrollment(courseId)}.
 *
 * <p>Record DTO — contains only the fields needed to display enrollment options
 * and a call-to-action button. No full course details.
 *
 * @param price         display price (formatted string)
 * @param discountPrice discounted price (formatted string, or null if no discount)
 * @param enrollmentUrl direct enrollment URL
 * @param nextSchedule   next available session schedule (simplified)
 */
public record EnrollmentResult(String price, String discountPrice, String enrollmentUrl, NextSchedule nextSchedule) {

    /**
     * Simplified next-session schedule for enrollment display.
     *
     * @param nextStartDate next session start date (ISO-8601 string, or null)
     * @param duration      session duration label (e.g. "12 weeks")
     */
    public record NextSchedule(String nextStartDate, String duration) {}
}
