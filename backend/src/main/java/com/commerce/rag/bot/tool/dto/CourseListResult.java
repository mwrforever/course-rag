package com.commerce.rag.bot.tool.dto;

import java.util.List;

/**
 * Paginated course listing result returned by {@code listCourses(keyword, page)}.
 *
 * <p>Record DTO — only core display fields, no sensitive or internal data.
 *
 * @param page     current page number (1-based)
 * @param pageSize page size
 * @param total    total matching course count
 * @param courses  list of course summaries on the current page
 */
public record CourseListResult(int page, int pageSize, long total, List<CourseSummary> courses) {

    /**
     * Compact course summary for listing display.
     *
     * @param courseId      unique course identifier
     * @param title         course title
     * @param category      course category (e.g. "AI", "Backend")
     * @param price         display price (formatted string, e.g. "\u00a51999")
     * @param discount      discount label (e.g. "\u00a5200 off" or null)
     * @param difficulty     difficulty level (e.g. "Beginner", "Intermediate")
     * @param status         enrollment status (e.g. "Open", "Full")
     * @param nextStartDate  next session start date (ISO-8601 string, or null)
     * @param tags           course tags for filtering and display
     */
    public record CourseSummary(
            String courseId,
            String title,
            String category,
            String price,
            String discount,
            String difficulty,
            String status,
            String nextStartDate,
            List<String> tags) {}
}
