/**
 * Staff shifts and attendance (ADR 0156): the rota and the timesheet of non-courier staff.
 *
 * <p>A timesheet and never a permission -- nothing here refuses an order action, a kitchen action
 * or a sign-in for want of an open shift. Paid hours come from the shift (the fact), never from the
 * roster (the plan), and leave the platform as facts and a payroll export, never as pay.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Workforce")
package uz.horecaos.platform.workforce;
