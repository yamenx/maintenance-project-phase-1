/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * The Apereo Foundation licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.unitime.timetable.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.gwt.command.client.GwtRpcException;
import org.unitime.timetable.gwt.command.client.GwtRpcResponseList;
import org.unitime.timetable.gwt.command.server.GwtRpcImplements;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface;
import org.unitime.timetable.gwt.shared.EventInterface.ApprovalStatus;
import org.unitime.timetable.gwt.shared.EventInterface.MeetingInterface;
import org.unitime.timetable.gwt.shared.EventInterface.RelatedObjectInterface;
import org.unitime.timetable.gwt.shared.OnlineSectioningInterface.WaitListMode;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.Conflict;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.CourseAssignment;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.Enrollment;
import org.unitime.timetable.gwt.shared.EventInterface.EventEnrollmentsRpcRequest;
import org.unitime.timetable.model.Advisor;
import org.unitime.timetable.model.ClassEvent;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseDemand;
import org.unitime.timetable.model.CourseEvent;
import org.unitime.timetable.model.CourseRequest;
import org.unitime.timetable.model.DepartmentalInstructor;
import org.unitime.timetable.model.Event;
import org.unitime.timetable.model.ExamEvent;
import org.unitime.timetable.model.ExamOwner;
import org.unitime.timetable.model.Meeting;
import org.unitime.timetable.model.StudentAccomodation;
import org.unitime.timetable.model.StudentAreaClassificationMajor;
import org.unitime.timetable.model.StudentAreaClassificationMinor;
import org.unitime.timetable.model.StudentClassEnrollment;
import org.unitime.timetable.model.StudentGroup;
import org.unitime.timetable.model.StudentSectioningStatus;
import org.unitime.timetable.model.TimetableManager;
import org.unitime.timetable.model.Event.MultiMeeting;
import org.unitime.timetable.model.StudentSectioningStatus.Option;
import org.unitime.timetable.model.dao.ClassEventDAO;
import org.unitime.timetable.model.dao.Class_DAO;
import org.unitime.timetable.model.dao.CourseEventDAO;
import org.unitime.timetable.model.dao.EventDAO;
import org.unitime.timetable.model.dao.ExamEventDAO;
import org.unitime.timetable.onlinesectioning.custom.CustomStudentEnrollmentHolder;
import org.unitime.timetable.security.rights.Right;
import org.unitime.timetable.solver.exam.ui.ExamAssignment;
import org.unitime.timetable.solver.exam.ui.ExamAssignmentInfo;
import org.unitime.timetable.solver.exam.ui.ExamAssignmentInfo.BackToBackConflict;
import org.unitime.timetable.solver.exam.ui.ExamAssignmentInfo.DirectConflict;
import org.unitime.timetable.solver.exam.ui.ExamAssignmentInfo.MoreThanTwoADayConflict;
import org.unitime.timetable.util.NameFormat;

/**
 * @author Tomas Muller
 */
@GwtRpcImplements(EventEnrollmentsRpcRequest.class)
public class EventEnrollmentsBackend extends EventAction<EventEnrollmentsRpcRequest, GwtRpcResponseList<ClassAssignmentInterface.Enrollment>> {

    // -------------------------------------------------------------------------
    // execute
    // -------------------------------------------------------------------------

    @Override
    public GwtRpcResponseList<Enrollment> execute(EventEnrollmentsRpcRequest request, EventContext context) {
        if (request.hasRelatedObjects()) {
            return executeForRelatedObjects(request, context);
        } else if (request.hasEventId()) {
            return executeForEvent(request, context);
        }
        return null;
    }

    /** Handles the case where the request carries related-object data. */
    private GwtRpcResponseList<Enrollment> executeForRelatedObjects(EventEnrollmentsRpcRequest request, EventContext context) {
        context.checkPermission(Right.EventAddCourseRelated);

        Map<Long, List<Meeting>> conflicts = null;
        HashSet<StudentClassEnrollment> enrollments = new HashSet<StudentClassEnrollment>();

        for (RelatedObjectInterface related : request.getRelatedObjects()) {
            enrollments.addAll(getStudentClassEnrollments(related));
            if (request.hasMeetings()) {
                if (conflicts == null) conflicts = new HashMap<Long, List<Meeting>>();
                for (MeetingInterface meeting : request.getMeetings()) {
                    if (isActiveMeeting(meeting))
                        computeConflicts(conflicts, meeting, related, request.getEventId());
                }
            }
        }

        return convert(enrollments, conflicts,
                context.hasPermission(Right.EnrollmentsShowExternalId),
                context.hasPermission(Right.CourseRequests),
                context.hasPermission(Right.SchedulingAssistant));
    }

    private static boolean isActiveMeeting(MeetingInterface meeting) {
        return meeting.getApprovalStatus() == ApprovalStatus.Approved
                || meeting.getApprovalStatus() == ApprovalStatus.Pending;
    }

    /** Handles the case where the request carries an event-id. */
    private GwtRpcResponseList<Enrollment> executeForEvent(EventEnrollmentsRpcRequest request, EventContext context) {
        org.hibernate.Session hibSession = EventDAO.getInstance().getSession();
        Event event = EventDAO.getInstance().get(request.getEventId());

        if (event == null) {
            return executeForNullEvent(request, context);
        }

        context.checkPermission(event, Right.EventDetail);

        Collection<StudentClassEnrollment> enrollments = event.getStudentClassEnrollments();
        if (enrollments == null || enrollments.isEmpty()) return null;

        boolean canShowExtId    = context.hasPermission(Right.EnrollmentsShowExternalId);
        boolean canRegister     = context.hasPermission(Right.CourseRequests);
        boolean canUseAssistant = context.hasPermission(Right.SchedulingAssistant);

        switch (event.getEventType()) {
            case Event.sEventTypeClass:
                return convert(enrollments,
                        computeConflicts(event instanceof ClassEvent ? (ClassEvent) event
                                : ClassEventDAO.getInstance().get(event.getUniqueId(), hibSession)),
                        canShowExtId, canRegister, canUseAssistant);

            case Event.sEventTypeFinalExam:
            case Event.sEventTypeMidtermExam:
                ExamEvent examEvent = event instanceof ExamEvent ? (ExamEvent) event
                        : ExamEventDAO.getInstance().get(event.getUniqueId(), hibSession);
                GwtRpcResponseList<Enrollment> ret = convert(enrollments,
                        computeConflicts(examEvent), canShowExtId, canRegister, canUseAssistant);
                addExamConflicts(ret, examEvent);
                return ret;

            case Event.sEventTypeCourse:
                return convert(enrollments,
                        computeConflicts(event instanceof CourseEvent ? (CourseEvent) event
                                : CourseEventDAO.getInstance().get(event.getUniqueId(), hibSession)),
                        canShowExtId, canRegister, canUseAssistant);

            default:
                return null;
        }
    }

    /** Handles negative / missing event IDs (arrange-hour classes). */
    private GwtRpcResponseList<Enrollment> executeForNullEvent(EventEnrollmentsRpcRequest request, EventContext context) {
        if (request.getEventId() >= 0)
            throw new GwtRpcException(MESSAGES.errorBadEventId());

        Class_ clazz = Class_DAO.getInstance().get(-request.getEventId());
        if (clazz == null) throw new GwtRpcException(MESSAGES.errorBadEventId());

        if (!context.hasPermission(clazz, Right.ClassDetail))
            context.checkPermission(clazz, Right.EventDetailArrangeHourClass);

        Collection<StudentClassEnrollment> enrollments = clazz.getStudentEnrollments();
        if (enrollments == null || enrollments.isEmpty()) return null;

        return convert(enrollments, null,
                context.hasPermission(Right.EnrollmentsShowExternalId),
                context.hasPermission(Right.CourseRequests),
                context.hasPermission(Right.SchedulingAssistant));
    }

    // -------------------------------------------------------------------------
    // Exam conflict decoration
    // -------------------------------------------------------------------------

    private void addExamConflicts(GwtRpcResponseList<Enrollment> ret, ExamEvent examEvent) {
        ExamAssignmentInfo assignment = new ExamAssignmentInfo(examEvent.getExam(), false);

        for (DirectConflict conflict : assignment.getDirectConflicts()) {
            ExamAssignment other = conflict.getOtherExam();
            if (other == null) continue;
            Conflict conf = buildExamConflict(other, "Direct", "dc");
            applyConflictToStudents(ret, conflict.getStudents(), conf);
        }

        if (ApplicationProperty.ExaminationReportsStudentBackToBacks.isTrue()) {
            for (BackToBackConflict conflict : assignment.getBackToBackConflicts()) {
                Conflict conf = buildExamConflict(conflict.getOtherExam(), "Back-To-Back", "b2b");
                applyConflictToStudents(ret, conflict.getStudents(), conf);
            }
        }

        for (MoreThanTwoADayConflict conflict : assignment.getMoreThanTwoADaysConflicts()) {
            Conflict conf = buildMoreThanTwoADayConflict(conflict);
            applyConflictToStudents(ret, conflict.getStudents(), conf);
        }
    }

    private Conflict buildExamConflict(ExamAssignment other, String type, String style) {
        Conflict conf = new Conflict();
        conf.setName(other.getExamName());
        conf.setType(type);
        conf.setDate(getDateFormat().format(other.getPeriod().getStartDate()));
        conf.setTime(other.getTime(false));
        conf.setRoom(other.getRoomsName(false, ", "));
        conf.setStyle(style);
        return conf;
    }

    private Conflict buildMoreThanTwoADayConflict(MoreThanTwoADayConflict conflict) {
        StringBuilder name = new StringBuilder();
        StringBuilder date = new StringBuilder();
        StringBuilder time = new StringBuilder();
        StringBuilder room = new StringBuilder();

        for (ExamAssignment other : conflict.getOtherExams()) {
            if (name.length() > 0) { name.append("<br>"); date.append("<br>"); time.append("<br>"); room.append("<br>"); }
            name.append(other.getExamName());
            date.append(getDateFormat().format(other.getPeriod().getStartDate()));
            time.append(other.getTime(false));
            room.append(other.getRoomsName(false, ", "));
        }

        Conflict conf = new Conflict();
        conf.setName(name.toString());
        conf.setType("&gt;2 A Day");
        conf.setDate(date.toString());
        conf.setTime(time.toString());
        conf.setRoom(room.toString());
        conf.setStyle("m2d");
        return conf;
    }

    private static void applyConflictToStudents(GwtRpcResponseList<Enrollment> enrollments,
            Iterable<Long> studentIds, Conflict conf) {
        for (Long studentId : studentIds) {
            for (Enrollment enrollment : enrollments) {
                if (enrollment.getStudent().getId() == studentId)
                    enrollment.addConflict(conf);
            }
        }
    }

    // -------------------------------------------------------------------------
    // getStudentClassEnrollments
    // -------------------------------------------------------------------------

    public static Collection<StudentClassEnrollment> getStudentClassEnrollments(RelatedObjectInterface relatedObject) {
        switch (relatedObject.getType()) {
            case Class:
                return EventDAO.getInstance().getSession().createQuery(
                        "select distinct e from StudentClassEnrollment e, StudentClassEnrollment f"
                        + " where f.clazz.uniqueId = :classId"
                        + " and e.courseOffering.instructionalOffering = f.courseOffering.instructionalOffering"
                        + " and e.student = f.student",
                        StudentClassEnrollment.class)
                        .setParameter("classId", relatedObject.getUniqueId())
                        .setCacheable(true).list();

            case Config:
                return EventDAO.getInstance().getSession().createQuery(
                        "select distinct e from StudentClassEnrollment e, StudentClassEnrollment f"
                        + " where f.clazz.schedulingSubpart.instrOfferingConfig.uniqueId = :configId"
                        + " and e.courseOffering.instructionalOffering = f.courseOffering.instructionalOffering"
                        + " and e.student = f.student",
                        StudentClassEnrollment.class)
                        .setParameter("configId", relatedObject.getUniqueId())
                        .setCacheable(true).list();

            case Course:
                return EventDAO.getInstance().getSession().createQuery(
                        "select e from StudentClassEnrollment e where e.courseOffering.uniqueId = :courseId",
                        StudentClassEnrollment.class)
                        .setParameter("courseId", relatedObject.getUniqueId())
                        .setCacheable(true).list();

            case Offering:
                return EventDAO.getInstance().getSession().createQuery(
                        "select e from StudentClassEnrollment e"
                        + " where e.courseOffering.instructionalOffering.uniqueId = :offeringId",
                        StudentClassEnrollment.class)
                        .setParameter("offeringId", relatedObject.getUniqueId())
                        .setCacheable(true).list();

            default:
                throw new GwtRpcException("Unsupported related object type " + relatedObject.getType());
        }
    }

    // -------------------------------------------------------------------------
    // computeConflicts (static, for related-objects path)
    // -------------------------------------------------------------------------

    public static void computeConflicts(Map<Long, List<Meeting>> conflicts,
            MeetingInterface meeting, RelatedObjectInterface relatedObject, Long eventId) {

        org.hibernate.Session hibSession = EventDAO.getInstance().getSession();
        String filterClause = buildRelatedObjectFilter(relatedObject);
        String paramName    = getRelatedObjectParamName(relatedObject);

        // class events
        addMeetingsToConflicts(conflicts, hibSession.createQuery(
                "select s1.student.uniqueId, m1"
                + " from StudentClassEnrollment s1, ClassEvent e1 inner join e1.meetings m1, StudentClassEnrollment s2"
                + " where " + filterClause + " and e1.clazz = s1.clazz and s1.student = s2.student"
                + " and m1.meetingDate = :meetingDate and m1.startPeriod < :stopPeriod"
                + " and :startPeriod < m1.stopPeriod and m1.approvalStatus = 1",
                Object[].class)
                .setParameter("meetingDate", meeting.getMeetingDate())
                .setParameter("startPeriod", meeting.getStartSlot())
                .setParameter("stopPeriod", meeting.getEndSlot())
                .setParameter(paramName, relatedObject.getUniqueId())
                .list());

        // exam events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            addMeetingsToConflicts(conflicts, hibSession.createQuery(
                    "select s1.student.uniqueId, m1"
                    + " from StudentClassEnrollment s1, ExamEvent e1 inner join e1.meetings m1"
                    + " inner join e1.exam.owners o1, StudentClassEnrollment s2"
                    + " where " + filterClause + " and s1.student = s2.student" + where(t1, 1)
                    + " and m1.meetingDate = :meetingDate and m1.startPeriod < :stopPeriod"
                    + " and :startPeriod < m1.stopPeriod and m1.approvalStatus = 1",
                    Object[].class)
                    .setParameter("meetingDate", meeting.getMeetingDate())
                    .setParameter("startPeriod", meeting.getStartSlot())
                    .setParameter("stopPeriod", meeting.getEndSlot())
                    .setParameter(paramName, relatedObject.getUniqueId())
                    .list());
        }

        // course events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            addMeetingsToConflicts(conflicts, hibSession.createQuery(
                    "select s1.student.uniqueId, m1"
                    + " from StudentClassEnrollment s1, CourseEvent e1 inner join e1.meetings m1"
                    + " inner join e1.relatedCourses o1, StudentClassEnrollment s2"
                    + " where " + filterClause + " and e1.uniqueId != :eventId and s1.student = s2.student"
                    + where(t1, 1)
                    + " and m1.meetingDate = :meetingDate and m1.startPeriod < :stopPeriod"
                    + " and :startPeriod < m1.stopPeriod and e1.reqAttendance = true and m1.approvalStatus = 1",
                    Object[].class)
                    .setParameter("meetingDate", meeting.getMeetingDate())
                    .setParameter("startPeriod", meeting.getStartSlot())
                    .setParameter("stopPeriod", meeting.getEndSlot())
                    .setParameter(paramName, relatedObject.getUniqueId())
                    .setParameter("eventId", eventId == null ? -1L : eventId)
                    .list());
        }
    }

    /** Returns the HQL WHERE fragment for filtering by a related-object's identity. */
    private static String buildRelatedObjectFilter(RelatedObjectInterface relatedObject) {
        switch (relatedObject.getType()) {
            case Class:    return "s2.clazz.uniqueId = :classId";
            case Config:   return "s2.clazz.schedulingSubpart.instrOfferingConfig.uniqueId = :configId";
            case Course:   return "s2.courseOffering.uniqueId = :courseId";
            case Offering: return "s2.courseOffering.instructionalOffering.uniqueId = :offeringId";
            default:       throw new GwtRpcException("Unsupported related object type " + relatedObject.getType());
        }
    }

    private static String getRelatedObjectParamName(RelatedObjectInterface relatedObject) {
        switch (relatedObject.getType()) {
            case Class:    return "classId";
            case Config:   return "configId";
            case Course:   return "courseId";
            case Offering: return "offeringId";
            default:       throw new GwtRpcException("Unsupported related object type " + relatedObject.getType());
        }
    }

    // -------------------------------------------------------------------------
    // convert
    // -------------------------------------------------------------------------

    public GwtRpcResponseList<ClassAssignmentInterface.Enrollment> convert(
            Collection<StudentClassEnrollment> enrollments,
            Map<Long, List<Meeting>> conflicts,
            boolean canShowExtId, boolean canRegister, boolean canUseAssistant) {

        GwtRpcResponseList<ClassAssignmentInterface.Enrollment> converted = new GwtRpcResponseList<>();
        Map<String, String> approvedBy2name = new Hashtable<>();
        Hashtable<Long, ClassAssignmentInterface.Enrollment> student2enrollment = new Hashtable<>();

        NameFormat studentNameFormat    = NameFormat.fromReference(ApplicationProperty.OnlineSchedulingStudentNameFormat.value());
        NameFormat instructorNameFormat = NameFormat.fromReference(ApplicationProperty.OnlineSchedulingInstructorNameFormat.value());

        for (StudentClassEnrollment enrollment : enrollments) {
            ClassAssignmentInterface.Enrollment enrl = student2enrollment.get(enrollment.getStudent().getUniqueId());

            if (enrl == null) {
                enrl = buildEnrollment(enrollment, conflicts, approvedBy2name,
                        studentNameFormat, instructorNameFormat,
                        canShowExtId, canRegister, canUseAssistant);
                student2enrollment.put(enrollment.getStudent().getUniqueId(), enrl);
                converted.add(enrl);
            }

            addClassAssignment(enrl, enrollment);
        }
        return converted;
    }

    /** Builds a fresh {@link ClassAssignmentInterface.Enrollment} for one student. */
    private ClassAssignmentInterface.Enrollment buildEnrollment(
            StudentClassEnrollment enrollment,
            Map<Long, List<Meeting>> conflicts,
            Map<String, String> approvedBy2name,
            NameFormat studentNameFormat,
            NameFormat instructorNameFormat,
            boolean canShowExtId, boolean canRegister, boolean canUseAssistant) {

        ClassAssignmentInterface.Student st = buildStudent(enrollment, studentNameFormat, instructorNameFormat,
                canShowExtId, canRegister, canUseAssistant);

        ClassAssignmentInterface.Enrollment enrl = new ClassAssignmentInterface.Enrollment();
        enrl.setStudent(st);
        enrl.setEnrolledDate(enrollment.getTimestamp());
        enrl.setCourse(buildCourseAssignment(enrollment));

        if (enrollment.getCourseRequest() != null) {
            populateCourseRequestFields(enrl, enrollment, approvedBy2name);
        } else {
            enrl.setPriority(-1);
        }

        addMeetingConflictsToEnrollment(enrl, enrollment.getStudent().getUniqueId(), conflicts);
        return enrl;
    }

    private static ClassAssignmentInterface.Student buildStudent(
            StudentClassEnrollment enrollment,
            NameFormat studentNameFormat, NameFormat instructorNameFormat,
            boolean canShowExtId, boolean canRegister, boolean canUseAssistant) {

        ClassAssignmentInterface.Student st = new ClassAssignmentInterface.Student();
        st.setId(enrollment.getStudent().getUniqueId());
        st.setExternalId(enrollment.getStudent().getExternalUniqueId());
        st.setCanShowExternalId(canShowExtId);
        st.setCanRegister(canRegister);
        st.setCanUseAssistant(canUseAssistant);
        st.setName(studentNameFormat.format(enrollment.getStudent()));
        st.setEmail(enrollment.getStudent().getEmail());
        st.setCanSelect(st.getEmail() != null && !st.getEmail().isEmpty());

        for (StudentAreaClassificationMajor acm : new TreeSet<>(enrollment.getStudent().getAreaClasfMajors())) {
            st.addArea(acm.getAcademicArea().getAcademicAreaAbbreviation(), acm.getAcademicArea().getTitle());
            st.addClassification(acm.getAcademicClassification().getCode(), acm.getAcademicClassification().getName());
            st.addMajor(acm.getMajor().getCode(), acm.getMajor().getName());
            st.addConcentration(acm.getConcentration() == null ? null : acm.getConcentration().getCode(),
                    acm.getConcentration() == null ? null : acm.getConcentration().getName());
            st.addDegree(acm.getDegree() == null ? null : acm.getDegree().getReference(),
                    acm.getDegree() == null ? null : acm.getDegree().getLabel());
            st.addProgram(acm.getProgram() == null ? null : acm.getProgram().getReference(),
                    acm.getProgram() == null ? null : acm.getProgram().getLabel());
            st.addCampus(acm.getCampus() == null ? null : acm.getCampus().getReference(),
                    acm.getCampus() == null ? null : acm.getCampus().getLabel());
        }
        st.setDefaultCampus(enrollment.getStudent().getSession().getAcademicInitiative());

        for (StudentAreaClassificationMinor acm : new TreeSet<>(enrollment.getStudent().getAreaClasfMinors())) {
            st.addMinor(acm.getMinor().getCode(), acm.getMinor().getName());
        }
        for (StudentGroup g : enrollment.getStudent().getGroups()) {
            if (g.getType() == null)
                st.addGroup(g.getGroupAbbreviation(), g.getGroupName());
            else
                st.addGroup(g.getType().getReference(), g.getGroupAbbreviation(), g.getGroupName());
        }
        for (StudentAccomodation a : enrollment.getStudent().getAccomodations()) {
            st.addAccommodation(a.getAbbreviation(), a.getName());
        }
        for (Advisor a : enrollment.getStudent().getAdvisors()) {
            if (a.getLastName() != null)
                st.addAdvisor(instructorNameFormat.format(a));
        }

        StudentSectioningStatus status = enrollment.getStudent().getEffectiveStatus();
        if (CustomStudentEnrollmentHolder.isAllowWaitListing() && (status == null || status.hasOption(Option.waitlist))) {
            st.setWaitListMode(WaitListMode.WaitList);
        } else if (status != null && status.hasOption(Option.nosubs)) {
            st.setWaitListMode(WaitListMode.NoSubs);
        } else {
            st.setWaitListMode(WaitListMode.None);
        }
        return st;
    }

    private static CourseAssignment buildCourseAssignment(StudentClassEnrollment enrollment) {
        CourseAssignment c = new CourseAssignment();
        c.setCourseId(enrollment.getCourseOffering().getUniqueId());
        c.setParentCourseId(enrollment.getCourseOffering().getParentOffering() == null
                ? null : enrollment.getCourseOffering().getParentOffering().getUniqueId());
        c.setSubject(enrollment.getCourseOffering().getSubjectAreaAbbv());
        c.setCourseNbr(enrollment.getCourseOffering().getCourseNbr());
        c.setHasCrossList(enrollment.getCourseOffering().getInstructionalOffering().hasCrossList());
        c.setCanWaitList(enrollment.getCourseOffering().getInstructionalOffering().effectiveWaitList());
        return c;
    }

    private static void populateCourseRequestFields(ClassAssignmentInterface.Enrollment enrl,
            StudentClassEnrollment enrollment, Map<String, String> approvedBy2name) {

        CourseRequest cr = enrollment.getCourseRequest();
        enrl.setPriority(1 + cr.getCourseDemand().getPriority());

        if (cr.getCourseDemand().getCourseRequests().size() > 1) {
            CourseRequest first = getFirstCourseRequest(cr.getCourseDemand());
            if (!first.equals(cr))
                enrl.setAlternative(first.getCourseOffering().getCourseName());
        }

        if (cr.getCourseDemand().isAlternative()) {
            enrl.setAlternative(resolveAlternativeName(enrollment));
        }

        enrl.setRequestedDate(cr.getCourseDemand().getTimestamp());
        enrl.setCritical(cr.getCourseDemand().getEffectiveCritical().ordinal());
        enrl.setApprovedDate(enrollment.getApprovedDate());

        if (enrollment.getApprovedBy() != null) {
            enrl.setApprovedBy(resolveApproverName(enrollment, approvedBy2name));
        }
    }

    private static CourseRequest getFirstCourseRequest(CourseDemand demand) {
        CourseRequest first = null;
        for (CourseRequest r : demand.getCourseRequests()) {
            if (first == null || r.getOrder().compareTo(first.getOrder()) < 0) first = r;
        }
        return first;
    }

    private static String resolveAlternativeName(StudentClassEnrollment enrollment) {
        CourseDemand first = enrollment.getCourseRequest().getCourseDemand();
        demands:
        for (CourseDemand cd : enrollment.getStudent().getCourseDemands()) {
            if (!cd.isAlternative() && cd.getPriority().compareTo(first.getPriority()) < 0
                    && !cd.getCourseRequests().isEmpty()) {
                for (CourseRequest cr : cd.getCourseRequests())
                    if (cr.getClassEnrollments().isEmpty()) continue demands;
                first = cd;
            }
        }
        CourseRequest alt = getFirstCourseRequest(first);
        return alt.getCourseOffering().getCourseName();
    }

    private static String resolveApproverName(StudentClassEnrollment enrollment,
            Map<String, String> approvedBy2name) {

        String name = approvedBy2name.get(enrollment.getApprovedBy());
        if (name != null) return name;

        TimetableManager mgr = EventDAO.getInstance().getSession()
                .createQuery("from TimetableManager where externalUniqueId = :externalId", TimetableManager.class)
                .setParameter("externalId", enrollment.getApprovedBy())
                .setMaxResults(1).uniqueResult();
        if (mgr != null) {
            name = mgr.getName();
        } else {
            DepartmentalInstructor instr = EventDAO.getInstance().getSession()
                    .createQuery("from DepartmentalInstructor where externalUniqueId = :externalId"
                            + " and department.session.uniqueId = :sessionId", DepartmentalInstructor.class)
                    .setParameter("externalId", enrollment.getApprovedBy())
                    .setParameter("sessionId", enrollment.getStudent().getSession().getUniqueId())
                    .setMaxResults(1).uniqueResult();
            if (instr != null) name = instr.nameLastNameFirst();
        }

        if (name != null) approvedBy2name.put(enrollment.getApprovedBy(), name);
        return name == null ? enrollment.getApprovedBy() : name;
    }

    private void addMeetingConflictsToEnrollment(ClassAssignmentInterface.Enrollment enrl,
            Long studentId, Map<Long, List<Meeting>> conflicts) {

        if (conflicts == null) return;
        List<Meeting> conf = conflicts.get(studentId);
        if (conf == null) return;

        Map<Event, TreeSet<Meeting>> eventMeetings = new HashMap<>();
        for (Meeting m : conf) {
            eventMeetings.computeIfAbsent(m.getEvent(), e -> new TreeSet<>()).add(m);
        }

        for (Event confEvent : new TreeSet<>(eventMeetings.keySet())) {
            enrl.addConflict(buildMeetingConflict(confEvent, eventMeetings.get(confEvent)));
        }
    }

    private Conflict buildMeetingConflict(Event confEvent, TreeSet<Meeting> meetings) {
        Conflict conflict = new Conflict();
        conflict.setName(confEvent.getEventName());
        conflict.setType(confEvent.getEventTypeAbbv());

        String lastDate = null, lastTime = null, lastRoom = null;
        for (MultiMeeting mm : Event.getMultiMeetings(meetings)) {
            String date = formatMultiMeetingDate(mm);
            String time = mm.getDays(CONSTANTS.days(), CONSTANTS.shortDays()) + " "
                    + (mm.getMeetings().first().isAllDay() ? "All Day"
                    : mm.getMeetings().first().startTime() + " - " + mm.getMeetings().first().stopTime());
            String room = mm.getMeetings().first().getLocation() == null ? ""
                    : mm.getMeetings().first().getLocation().getLabel();

            conflict.setDate(appendOrBreak(conflict.getDate(), lastDate, date));
            conflict.setTime(appendOrBreak(conflict.getTime(), lastTime, time));
            conflict.setRoom(appendOrBreak(conflict.getRoom(), lastRoom, room));

            lastDate = date; lastTime = time; lastRoom = room;
        }
        return conflict;
    }

    private String formatMultiMeetingDate(MultiMeeting mm) {
        String start = getDateFormat().format(mm.getMeetings().first().getMeetingDate());
        return mm.getMeetings().size() == 1 ? start
                : start + " - " + getDateFormat().format(mm.getMeetings().last().getMeetingDate());
    }

    /** Returns {@code existing + "<br>" + newValue} (or just {@code newValue} on first call). */
    private static String appendOrBreak(String existing, String lastValue, String newValue) {
        if (lastValue == null) return newValue;
        if (lastValue.equals(newValue)) return existing + "<br>";
        return existing + "<br>" + newValue;
    }

    private static void addClassAssignment(ClassAssignmentInterface.Enrollment enrl,
            StudentClassEnrollment enrollment) {
        ClassAssignmentInterface.ClassAssignment c = enrl.getCourse().addClassAssignment();
        c.setClassId(enrollment.getClazz().getUniqueId());
        c.setSection(enrollment.getClazz().getClassSuffix(enrollment.getCourseOffering()));
        if (c.getSection() == null)
            c.setSection(enrollment.getClazz().getSectionNumberString());
        c.setClassNumber(enrollment.getClazz().getSectionNumberString());
        c.setSubpart(enrollment.getClazz().getSchedulingSubpart().getItypeDesc());
    }

    // -------------------------------------------------------------------------
    // where() helper
    // -------------------------------------------------------------------------

    private static String where(int type, int idx) {
        switch (type) {
            case ExamOwner.sOwnerTypeClass:
                return " and o" + idx + ".ownerType = " + type + " and o" + idx + ".ownerId = s" + idx + ".clazz.uniqueId";
            case ExamOwner.sOwnerTypeConfig:
                return " and o" + idx + ".ownerType = " + type + " and o" + idx + ".ownerId = s" + idx + ".clazz.schedulingSubpart.instrOfferingConfig.uniqueId";
            case ExamOwner.sOwnerTypeCourse:
                return " and o" + idx + ".ownerType = " + type + " and o" + idx + ".ownerId = s" + idx + ".courseOffering.uniqueId";
            case ExamOwner.sOwnerTypeOffering:
                return " and o" + idx + ".ownerType = " + type + " and o" + idx + ".ownerId = s" + idx + ".courseOffering.instructionalOffering.uniqueId";
            default:
                return "";
        }
    }

    // -------------------------------------------------------------------------
    // addMeetingsToConflicts helper
    // -------------------------------------------------------------------------

    private static void addMeetingsToConflicts(Map<Long, List<Meeting>> conflicts, List<Object[]> rows) {
        for (Object[] o : rows) {
            Long studentId = (Long) o[0];
            Meeting meeting = (Meeting) o[1];
            conflicts.computeIfAbsent(studentId, id -> new ArrayList<>()).add(meeting);
        }
    }

    // -------------------------------------------------------------------------
    // computeConflicts (instance, per event type)
    // -------------------------------------------------------------------------

    private Map<Long, List<Meeting>> computeConflicts(ClassEvent event) {
        Map<Long, List<Meeting>> conflicts = new HashMap<>();
        org.hibernate.Session hibSession = EventDAO.getInstance().getSession();

        // class events
        addMeetingsToConflicts(conflicts, hibSession.createQuery(
                "select s1.student.uniqueId, m1"
                + " from StudentClassEnrollment s1, ClassEvent e1 inner join e1.meetings m1,"
                + " ClassEvent e2 inner join e2.meetings m2, StudentClassEnrollment s2"
                + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                + " and e1.clazz = s1.clazz and e2.clazz = s2.clazz and s1.student = s2.student"
                + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                + " and m2.startPeriod < m1.stopPeriod and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                Object[].class)
                .setParameter("eventId", event.getUniqueId()).list());

        // examination events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            addMeetingsToConflicts(conflicts, hibSession.createQuery(
                    "select s1.student.uniqueId, m1"
                    + " from StudentClassEnrollment s1, ExamEvent e1 inner join e1.meetings m1"
                    + " inner join e1.exam.owners o1, ClassEvent e2 inner join e2.meetings m2, StudentClassEnrollment s2"
                    + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                    + " and e2.clazz = s2.clazz and s1.student = s2.student" + where(t1, 1)
                    + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                    + " and m2.startPeriod < m1.stopPeriod and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                    Object[].class)
                    .setParameter("eventId", event.getUniqueId()).list());
        }

        // course events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            addMeetingsToConflicts(conflicts, hibSession.createQuery(
                    "select s1.student.uniqueId, m1"
                    + " from StudentClassEnrollment s1, CourseEvent e1 inner join e1.meetings m1"
                    + " inner join e1.relatedCourses o1, ClassEvent e2 inner join e2.meetings m2, StudentClassEnrollment s2"
                    + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                    + " and e2.clazz = s2.clazz and s1.student = s2.student" + where(t1, 1)
                    + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                    + " and m2.startPeriod < m1.stopPeriod and e1.reqAttendance = true"
                    + " and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                    Object[].class)
                    .setParameter("eventId", event.getUniqueId()).list());
        }

        return conflicts;
    }

    private Map<Long, List<Meeting>> computeConflicts(ExamEvent event) {
        Map<Long, List<Meeting>> conflicts = new HashMap<>();
        org.hibernate.Session hibSession = EventDAO.getInstance().getSession();

        // class events
        for (int t2 = 0; t2 < ExamOwner.sOwnerTypes.length; t2++) {
            addMeetingsToConflicts(conflicts, hibSession.createQuery(
                    "select s1.student.uniqueId, m1"
                    + " from StudentClassEnrollment s1, ClassEvent e1 inner join e1.meetings m1,"
                    + " ExamEvent e2 inner join e2.meetings m2 inner join e2.exam.owners o2, StudentClassEnrollment s2"
                    + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                    + " and e1.clazz = s1.clazz and s1.student = s2.student" + where(t2, 2)
                    + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                    + " and m2.startPeriod < m1.stopPeriod and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                    Object[].class)
                    .setParameter("eventId", event.getUniqueId()).list());
        }

        // examination events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            for (int t2 = 0; t2 < ExamOwner.sOwnerTypes.length; t2++) {
                addMeetingsToConflicts(conflicts, hibSession.createQuery(
                        "select s1.student.uniqueId, m1"
                        + " from StudentClassEnrollment s1, ExamEvent e1 inner join e1.meetings m1"
                        + " inner join e1.exam.owners o1, ExamEvent e2 inner join e2.meetings m2"
                        + " inner join e2.exam.owners o2, StudentClassEnrollment s2"
                        + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                        + " and s1.student = s2.student and e1.exam.examType != e2.exam.examType"
                        + where(t1, 1) + where(t2, 2)
                        + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                        + " and m2.startPeriod < m1.stopPeriod and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                        Object[].class)
                        .setParameter("eventId", event.getUniqueId()).list());
            }
        }

        // course events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            for (int t2 = 0; t2 < ExamOwner.sOwnerTypes.length; t2++) {
                addMeetingsToConflicts(conflicts, hibSession.createQuery(
                        "select s1.student.uniqueId, m1"
                        + " from StudentClassEnrollment s1, CourseEvent e1 inner join e1.meetings m1"
                        + " inner join e1.relatedCourses o1, ExamEvent e2 inner join e2.meetings m2"
                        + " inner join e2.exam.owners o2, StudentClassEnrollment s2"
                        + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                        + " and s1.student = s2.student" + where(t1, 1) + where(t2, 2)
                        + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                        + " and m2.startPeriod < m1.stopPeriod and e1.reqAttendance = true"
                        + " and m1.approvalStatus = 1 and m2.approvalStatus = 1",
                        Object[].class)
                        .setParameter("eventId", event.getUniqueId()).list());
            }
        }

        return conflicts;
    }

    private Map<Long, List<Meeting>> computeConflicts(CourseEvent event) {
        Map<Long, List<Meeting>> conflicts = new HashMap<>();
        org.hibernate.Session hibSession = EventDAO.getInstance().getSession();

        // class events — BUG FIX: no outer loop over ExamOwner.sOwnerTypes.
        // ClassEvent has no exam owners; the extra loop caused duplicate meetings per student.
        addMeetingsToConflicts(conflicts, hibSession.createQuery(
                "select s1.student.uniqueId, m1"
                + " from StudentClassEnrollment s1, ClassEvent e1 inner join e1.meetings m1,"
                + " CourseEvent e2 inner join e2.meetings m2 inner join e2.relatedCourses o2, StudentClassEnrollment s2"
                + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                + " and e1.clazz = s1.clazz and s1.student = s2.student" + where(0, 2)
                + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                + " and m2.startPeriod < m1.stopPeriod and m2.approvalStatus <= 1 and m1.approvalStatus = 1",
                Object[].class)
                .setParameter("eventId", event.getUniqueId()).list());

        // examination events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            for (int t2 = 0; t2 < ExamOwner.sOwnerTypes.length; t2++) {
                addMeetingsToConflicts(conflicts, hibSession.createQuery(
                        "select s1.student.uniqueId, m1"
                        + " from StudentClassEnrollment s1, ExamEvent e1 inner join e1.meetings m1"
                        + " inner join e1.exam.owners o1, CourseEvent e2 inner join e2.meetings m2"
                        + " inner join e2.relatedCourses o2, StudentClassEnrollment s2"
                        + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                        + " and s1.student = s2.student" + where(t1, 1) + where(t2, 2)
                        + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                        + " and m2.startPeriod < m1.stopPeriod and m2.approvalStatus <= 1 and m1.approvalStatus = 1",
                        Object[].class)
                        .setParameter("eventId", event.getUniqueId()).list());
            }
        }

        // course events
        for (int t1 = 0; t1 < ExamOwner.sOwnerTypes.length; t1++) {
            for (int t2 = 0; t2 < ExamOwner.sOwnerTypes.length; t2++) {
                addMeetingsToConflicts(conflicts, hibSession.createQuery(
                        "select s1.student.uniqueId, m1"
                        + " from StudentClassEnrollment s1, CourseEvent e1 inner join e1.meetings m1"
                        + " inner join e1.relatedCourses o1, CourseEvent e2 inner join e2.meetings m2"
                        + " inner join e2.relatedCourses o2, StudentClassEnrollment s2"
                        + " where e2.uniqueId = :eventId and e1.uniqueId != e2.uniqueId"
                        + " and s1.student = s2.student" + where(t1, 1) + where(t2, 2)
                        + " and m1.meetingDate = m2.meetingDate and m1.startPeriod < m2.stopPeriod"
                        + " and m2.startPeriod < m1.stopPeriod and e1.reqAttendance = true"
                        + " and m1.approvalStatus = 1 and m2.approvalStatus <= 1",
                        Object[].class)
                        .setParameter("eventId", event.getUniqueId()).list());
            }
        }

        return conflicts;
    }
}
