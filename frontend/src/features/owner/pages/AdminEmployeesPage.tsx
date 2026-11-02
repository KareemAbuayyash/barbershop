import { useEffect, useState, type FormEvent } from 'react'
import { BadgeDollarSign, Plus, Users } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminEmployee, AdminEmployeePayment } from '../admin-api'

const paymentLabels: Record<string, string> = {
  SALARY: 'راتب',
  COMMISSION: 'عمولة',
  ADVANCE: 'سلفة',
  OTHER: 'أخرى',
}

function AdminEmployeesPage() {
  const [employees, setEmployees] = useState<AdminEmployee[]>([])
  const [payments, setPayments] = useState<AdminEmployeePayment[]>([])
  const [editing, setEditing] = useState<AdminEmployee | null>(null)
  const [selectedEmployee, setSelectedEmployee] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const load = async () => {
    try {
      const [employeeData, paymentData] = await Promise.all([
        apiRequest<AdminEmployee[]>('/api/admin/employees'),
        apiRequest<AdminEmployeePayment[]>('/api/admin/employee-payments'),
      ])
      setEmployees(employeeData)
      setPayments(paymentData)
      if (!selectedEmployee && employeeData.length) setSelectedEmployee(employeeData[0].id)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل بيانات الموظفين.'))
    }
  }
  useEffect(() => {
    let active = true
    void Promise.all([
      apiRequest<AdminEmployee[]>('/api/admin/employees'),
      apiRequest<AdminEmployeePayment[]>('/api/admin/employee-payments'),
    ]).then(([employeeData, paymentData]) => {
      if (!active) return
      setEmployees(employeeData)
      setPayments(paymentData)
      if (employeeData.length) setSelectedEmployee(employeeData[0].id)
    }).catch((cause: unknown) => {
      if (active) setError(getErrorMessage(cause, 'تعذر تحميل بيانات الموظفين.'))
    })
    return () => { active = false }
  }, [])

  const saveEmployee = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true)
    setError('')
    setNotice('')
    const employee = {
      name: String(data.get('name')).trim(),
      phoneNumber: String(data.get('phoneNumber')).trim() || null,
      jobTitle: String(data.get('jobTitle')).trim(),
      baseSalary: Number(data.get('baseSalary')),
      commissionPercent: Number(data.get('commissionPercent')),
      active: data.get('active') === 'on',
    }
    try {
      const path = editing ? `/api/admin/employees/${editing.id}` : '/api/admin/employees'
      await apiRequest(path, { method: editing ? 'PUT' : 'POST', body: JSON.stringify(employee) })
      setNotice(editing ? 'تم تحديث بيانات الموظف.' : 'تمت إضافة الموظف.')
      setEditing(null)
      form.reset()
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ بيانات الموظف.'))
    } finally { setBusy(false) }
  }

  const recordPayment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const employeeId = String(data.get('employeeId'))
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/employees/${employeeId}/payments`, {
        method: 'POST',
        body: JSON.stringify({
          amount: Number(data.get('amount')),
          paymentType: String(data.get('paymentType')),
          periodStart: String(data.get('periodStart')) || null,
          periodEnd: String(data.get('periodEnd')) || null,
          notes: String(data.get('notes')).trim() || null,
        }),
      })
      form.reset()
      setNotice('تم تسجيل مستحقات الموظف.')
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تسجيل مستحقات الموظف.'))
    } finally { setBusy(false) }
  }

  return (
    <div className="admin-page admin-two-column">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">الفريق والمستحقات</span><h2>الموظفون</h2></div>
          <span className="admin-result-count">{employees.length.toLocaleString('ar')} موظف</span>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {!employees.length ? (
          <div className="admin-empty"><Users size={24} /><h3>لا يوجد موظفون مسجلون</h3></div>
        ) : employees.map((employee) => (
          <article className="admin-employee-card" key={employee.id}>
            <div className="admin-employee-heading">
              <span className="admin-customer-avatar">{employee.name.slice(0, 1)}</span>
              <div><strong>{employee.name}</strong><small>{employee.jobTitle} · {employee.active ? 'نشط' : 'موقوف'}</small>
                <small dir="ltr">{employee.phoneNumber || '—'}</small></div>
              <button className="admin-secondary-button" type="button" onClick={() => setEditing(employee)}>تعديل</button>
            </div>
            <div className="admin-summary-chips">
              <span>الراتب {employee.baseSalary.toLocaleString('ar')} ₪</span>
              <span>العمولة {employee.commissionPercent}%</span>
              <span>عمولات مستحقة {(employee.commissionEarned - employee.commissionPaid).toLocaleString('ar')} ₪</span>
            </div>
          </article>
        ))}
        <h3 className="admin-subheading">سجل الرواتب والعمولات</h3>
        {payments.length ? payments.map((payment) => (
          <div className="admin-history-row" key={payment.id}>
            <span>{payment.employeeName}</span>
            <span>{paymentLabels[payment.paymentType] ?? payment.paymentType}</span>
            <strong>{payment.amount.toLocaleString('ar')} ₪</strong>
            <small>{new Date(payment.paidAt).toLocaleDateString('ar')}</small>
          </div>
        )) : <p className="admin-state">لا توجد عمليات دفع للموظفين.</p>}
      </section>

      <div className="admin-form-stack">
        <section className="admin-panel">
          <div className="admin-panel-heading"><div><span className="admin-eyebrow">{editing ? 'تحديث البيانات' : 'إضافة عضو للفريق'}</span>
            <h2>{editing ? 'تعديل موظف' : 'موظف جديد'}</h2></div><Plus size={20} /></div>
          <form className="admin-form" key={editing?.id ?? 'new'} onSubmit={(event) => void saveEmployee(event)}>
            <label>الاسم<input name="name" defaultValue={editing?.name} maxLength={150} required /></label>
            <label>رقم الهاتف<input name="phoneNumber" type="tel" defaultValue={editing?.phoneNumber ?? ''} maxLength={32} /></label>
            <label>المسمى الوظيفي<input name="jobTitle" defaultValue={editing?.jobTitle} maxLength={100} required /></label>
            <label>الراتب الأساسي (₪)<input name="baseSalary" type="number" min="0" step="0.01" defaultValue={editing?.baseSalary ?? 0} required /></label>
            <label>نسبة العمولة (%)<input name="commissionPercent" type="number" min="0" max="100" step="0.01" defaultValue={editing?.commissionPercent ?? 0} required /></label>
            <label className="admin-checkbox"><input type="checkbox" name="active" defaultChecked={editing?.active ?? true} /> موظف نشط</label>
            <button className="admin-primary-button" disabled={busy}>{editing ? 'حفظ التعديل' : 'إضافة الموظف'}</button>
            {editing && <button className="admin-secondary-button" type="button" onClick={() => setEditing(null)}>إلغاء</button>}
          </form>
        </section>

        <section className="admin-panel">
          <div className="admin-panel-heading"><div><span className="admin-eyebrow">الرواتب والسلف</span><h2>تسجيل دفعة</h2></div><BadgeDollarSign size={20} /></div>
          <form className="admin-form" onSubmit={(event) => void recordPayment(event)}>
            <label>الموظف<select name="employeeId" value={selectedEmployee}
              onChange={(event) => setSelectedEmployee(event.target.value)} required>
              {employees.filter((item) => item.active).map((employee) =>
                <option key={employee.id} value={employee.id}>{employee.name}</option>)}
            </select></label>
            <label>نوع الدفعة<select name="paymentType">
              {Object.entries(paymentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <label>المبلغ (₪)<input name="amount" type="number" min="0.01" step="0.01" required /></label>
            <label>الفترة من (اختياري)<input name="periodStart" type="date" onClick={(event) => event.currentTarget.showPicker?.()} /></label>
            <label>الفترة إلى (اختياري)<input name="periodEnd" type="date" onClick={(event) => event.currentTarget.showPicker?.()} /></label>
            <label>ملاحظة<input name="notes" maxLength={1000} /></label>
            <button className="admin-primary-button" disabled={busy || !employees.some((item) => item.active)}>تسجيل الدفعة</button>
          </form>
          <p className="admin-form-note">تُسجّل العمولة عند تحصيل دفعة لحجز مرتبط بالموظف. دفع العمولة لا يتجاوز الرصيد المستحق.</p>
        </section>
      </div>
    </div>
  )
}

export default AdminEmployeesPage
