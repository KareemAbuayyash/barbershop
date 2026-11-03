import { useEffect, useState, type FormEvent } from 'react'
import { Save, Settings } from 'lucide-react'
import { apiRequest, getErrorMessage, type BookingSettings } from '../../../lib/api'
import { useAuth } from '../../auth/useAuth'
import type { AdminShopProfile } from '../admin-api'

type BookingModeSetting = Exclude<keyof BookingSettings, 'updatedAt'>
type DayKey = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
type DaySchedule = { opensAt: string; closesAt: string } | null
type WeeklySchedule = Record<DayKey, DaySchedule>
type HoursValidation = { day?: DayKey; message: string }

interface OwnerProfile {
  username: string
}

const weekdays: Array<{ key: DayKey; label: string }> = [
  { key: 'SUNDAY', label: 'الأحد' },
  { key: 'MONDAY', label: 'الاثنين' },
  { key: 'TUESDAY', label: 'الثلاثاء' },
  { key: 'WEDNESDAY', label: 'الأربعاء' },
  { key: 'THURSDAY', label: 'الخميس' },
  { key: 'FRIDAY', label: 'الجمعة' },
  { key: 'SATURDAY', label: 'السبت' },
]

const closedSchedule = (): WeeklySchedule => ({
  MONDAY: null, TUESDAY: null, WEDNESDAY: null, THURSDAY: null,
  FRIDAY: null, SATURDAY: null, SUNDAY: null,
})

function parseSchedule(value: string): WeeklySchedule {
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('إعداد ساعات العمل غير صالح. أعد ضبط ساعات الأسبوع.')
  }
  const schedule = closedSchedule()
  for (const { key } of weekdays) {
    const day = (parsed as Record<string, unknown>)[key]
    if (day === undefined || day === null) continue
    if (typeof day !== 'object' || Array.isArray(day)) {
      throw new Error('تعذر قراءة ساعات العمل المحفوظة.')
    }
    const value = day as Record<string, unknown>
    if (typeof value.opensAt !== 'string' || typeof value.closesAt !== 'string') {
      throw new Error('تعذر قراءة ساعات العمل المحفوظة.')
    }
    schedule[key] = { opensAt: value.opensAt, closesAt: value.closesAt }
  }
  return schedule
}

function validateHours(hours: WeeklySchedule): HoursValidation | null {
  const openDays = weekdays.filter(({ key }) => hours[key] !== null)
  if (openDays.length === 0) {
    return { message: 'اختر يوم عمل واحداً على الأقل وحدد ساعات افتتاحه وإغلاقه.' }
  }
  for (const { key, label } of openDays) {
    const day = hours[key]
    if (!day?.opensAt) return { day: key, message: `${label}: حدد وقت الافتتاح.` }
    if (!day.closesAt) return { day: key, message: `${label}: حدد وقت الإغلاق.` }
    const timePattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/
    if (!timePattern.test(day.opensAt) || !timePattern.test(day.closesAt)) {
      return { day: key, message: `${label}: استخدم وقتاً صحيحاً بنظام 24 ساعة.` }
    }
    if (day.opensAt === day.closesAt
      || (day.closesAt < day.opensAt && day.closesAt !== '00:00')) {
      return { day: key, message: `${label}: يجب أن يكون الإغلاق بعد الافتتاح، أو 12:00 AM عند نهاية اليوم.` }
    }
  }
  return null
}

const bookingModes: Array<{ key: BookingModeSetting; label: string }> = [
  { key: 'exactAppointmentEnabled', label: 'المواعيد المحددة' },
  { key: 'confirmationRequestEnabled', label: 'طلبات تأكيد الموعد' },
  { key: 'dayQueueEnabled', label: 'قائمة انتظار يومية' },
  { key: 'newBookingsDisabled', label: 'إيقاف الحجوزات الجديدة مؤقتاً' },
]

function AdminSettingsPage() {
  const { updateUsername } = useAuth()
  const [profile, setProfile] = useState<AdminShopProfile | null>(null)
  const [bookingSettings, setBookingSettings] = useState<BookingSettings | null>(null)
  const [hours, setHours] = useState<WeeklySchedule>(closedSchedule)
  const [hoursValidation, setHoursValidation] = useState<HoursValidation | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [ownerProfile, setOwnerProfile] = useState<OwnerProfile | null>(null)
  const [ownerUsername, setOwnerUsername] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [ownerError, setOwnerError] = useState('')
  const [ownerNotice, setOwnerNotice] = useState('')
  const [ownerBusy, setOwnerBusy] = useState(false)
  const ownerAccountChangeRequiresPassword = Boolean(newPassword)
    || Boolean(ownerProfile && ownerUsername.trim().toLowerCase() !== ownerProfile.username)

  useEffect(() => {
    void Promise.all([
      apiRequest<AdminShopProfile>('/api/admin/settings'),
      apiRequest<BookingSettings>('/api/booking-settings'),
    ]).then(([shop, bookings]) => {
      setProfile(shop)
      setBookingSettings(bookings)
      try {
        setHours(parseSchedule(shop.openingHours))
      } catch (cause) {
        setError(getErrorMessage(cause, 'تعذر قراءة ساعات العمل المحفوظة.'))
      }
    }).catch((cause: unknown) => setError(getErrorMessage(cause, 'تعذر تحميل إعدادات المحل.')))
  }, [])

  useEffect(() => {
    void apiRequest<OwnerProfile>('/api/admin/owner')
      .then((owner) => {
        setOwnerProfile(owner)
        setOwnerUsername(owner.username)
      })
      .catch((cause: unknown) => setOwnerError(getErrorMessage(cause, 'تعذر تحميل بيانات حساب المالك.')))
  }, [])

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!profile || !bookingSettings) return
    const data = new FormData(event.currentTarget)
    const invalidHours = validateHours(hours)
    if (invalidHours) {
      setHoursValidation(invalidHours)
      setError(invalidHours.message)
      return
    }
    setBusy(true)
    setError('')
    setHoursValidation(null)
    setNotice('')
    try {
      const updatedProfile = await apiRequest<AdminShopProfile>('/api/admin/settings', {
        method: 'PUT',
        body: JSON.stringify({
          phoneNumber: String(data.get('phoneNumber')).trim() || null,
          address: String(data.get('address')).trim() || null,
          openingHours: JSON.stringify(hours),
          ...Object.fromEntries(bookingModes.map(({ key }) =>
            [key, data.get(key) === 'on'])),
          sameDayQueueEnabled: false,
        }),
      })
      setProfile(updatedProfile)
      setBookingSettings({
        ...bookingSettings,
        ...Object.fromEntries(bookingModes.map(({ key }) => [key, data.get(key) === 'on'])),
        sameDayQueueEnabled: false,
      })
      setNotice('تم حفظ إعدادات المحل والحجز.')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ الإعدادات. تحقق من الاتصال وحاول مجدداً.'))
    } finally { setBusy(false) }
  }

  const saveOwnerAccount = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setOwnerError('')
    setOwnerNotice('')
    setOwnerBusy(true)
    try {
      const owner = await apiRequest<OwnerProfile>('/api/admin/owner', {
        method: 'PUT',
        body: JSON.stringify({
          username: ownerUsername.trim(),
          currentPassword: ownerAccountChangeRequiresPassword ? currentPassword : null,
          newPassword: newPassword || null,
        }),
      })
      setOwnerProfile(owner)
      setOwnerUsername(owner.username)
      setCurrentPassword('')
      setNewPassword('')
      updateUsername(owner.username)
      setOwnerNotice('تم تحديث بيانات حساب المالك بنجاح.')
    } catch (cause) {
      setOwnerError(getErrorMessage(cause, 'تعذر تحديث بيانات حساب المالك.'))
    } finally { setOwnerBusy(false) }
  }

  if (!profile || !bookingSettings) {
    return <div className="admin-page">{error
      ? <p className="admin-message admin-message--error" role="alert">{error}</p>
      : <p className="admin-state" role="status">جارٍ تحميل الإعدادات...</p>}</div>
  }

  return (
    <div className="admin-page admin-settings-page">
      <section className="admin-panel admin-settings-main">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">بيانات المحل والحجز</span>
          <h2>إعدادات المحل</h2></div><Settings size={20} /></div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        <form className="admin-settings-grid" onSubmit={(event) => void save(event)}>
          <fieldset className="admin-panel admin-settings-section admin-settings-shop">
            <legend>معلومات المحل</legend>
            <label>رقم الهاتف<input name="phoneNumber" type="tel" maxLength={32} defaultValue={profile.phoneNumber ?? ''} /></label>
            <label>العنوان<input name="address" maxLength={300} defaultValue={profile.address ?? ''} /></label>
            <div className="admin-hours-editor">
              <h3>ساعات العمل الأسبوعية</h3>
              <p className="admin-form-note">المواعيد وقائمة الانتظار متاحة فقط في الأيام والساعات المفتوحة. عند إغلاق يوم لديه حجوزات قادمة، تصبح بانتظار التأكيد ويصل أصحابها إشعار لتحديد موعد جديد.</p>
              {weekdays.map(({ key, label }) => {
                const day = hours[key]
                return (
                  <div className="admin-hours-row" key={key}>
                    <label className="admin-hours-day">
                      <input type="checkbox" checked={day !== null}
                        onChange={(event) => {
                          setHours((current) => ({
                            ...current,
                            [key]: event.target.checked ? { opensAt: '', closesAt: '' } : null,
                          }))
                          setHoursValidation(null)
                        }} />
                      <span>{label}</span>
                    </label>
                    {day ? (
                      <div className="admin-hours-times">
                        <label>من<input aria-label={`افتتاح ${label}`} type="time" step={60}
                          value={day.opensAt} aria-invalid={hoursValidation?.day === key}
                          onChange={(event) => {
                            setHours((current) => ({
                              ...current,
                              [key]: { ...day, opensAt: event.target.value },
                            }))
                            setHoursValidation(null)
                          }} /></label>
                        <label>إلى<input aria-label={`إغلاق ${label}`} type="time" step={60}
                          value={day.closesAt} aria-invalid={hoursValidation?.day === key}
                          onChange={(event) => {
                            setHours((current) => ({
                              ...current,
                              [key]: { ...day, closesAt: event.target.value },
                            }))
                            setHoursValidation(null)
                          }} /></label>
                      </div>
                    ) : <span className="admin-hours-closed">مغلق</span>}
                    {hoursValidation?.day === key
                      && <p className="admin-message admin-message--error" role="alert">{hoursValidation.message}</p>}
                  </div>
                )
              })}
            </div>
          </fieldset>
          <fieldset className="admin-panel admin-settings-section admin-settings-booking">
            <legend>طرق الحجز</legend>
            {bookingModes.map(({ key, label }) => (
              <label className="admin-toggle-row" key={key}>
                <span>{label}</span>
                <input type="checkbox" name={key} defaultChecked={bookingSettings[key]} />
              </label>
            ))}
            <p className="admin-form-note">إيقاف جميع الطرق أو إيقاف الحجوزات الجديدة يمنع إنشاء حجوزات للزوار.</p>
          </fieldset>
          <button className="admin-primary-button admin-settings-save" disabled={busy}>
            <Save size={17} /> {busy ? 'جارٍ الحفظ...' : 'حفظ الإعدادات'}
          </button>
        </form>
      </section>
      <section className="admin-panel admin-owner-security">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">حماية الحساب</span><h2>بيانات المالك</h2></div>
        </div>
        {ownerError && <p className="admin-message admin-message--error" role="alert">{ownerError}</p>}
        {ownerNotice && <p className="admin-message" role="status">{ownerNotice}</p>}
        {ownerProfile && (
          <form className="admin-form admin-owner-account-form" onSubmit={(event) => void saveOwnerAccount(event)}>
            <label>اسم المستخدم
              <input value={ownerUsername} minLength={3} maxLength={64}
                pattern="[A-Za-z0-9._-]{3,64}" autoComplete="username" required
                onChange={(event) => setOwnerUsername(event.target.value)} />
            </label>
            <label>كلمة المرور الحالية
              <input type="password" value={currentPassword} maxLength={72}
                autoComplete="current-password" required={ownerAccountChangeRequiresPassword}
                onChange={(event) => setCurrentPassword(event.target.value)} />
            </label>
            <label>كلمة المرور الجديدة (اختياري)
              <input type="password" value={newPassword} minLength={8} maxLength={72}
                autoComplete="new-password" onChange={(event) => setNewPassword(event.target.value)} />
            </label>
            <p className="admin-form-note admin-owner-security-note">لتغيير اسم المستخدم أو كلمة المرور، أدخل كلمة المرور الحالية. تغيير كلمة المرور يتطلب أيضاً كلمة مرور جديدة من 8 أحرف على الأقل.</p>
            <button className="admin-primary-button admin-owner-security-save" disabled={ownerBusy}>
              {ownerBusy ? 'جارٍ التحديث...' : 'حفظ بيانات المالك'}
            </button>
          </form>
        )}
      </section>
    </div>
  )
}

export default AdminSettingsPage
