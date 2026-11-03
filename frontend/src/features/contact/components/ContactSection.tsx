import { useEffect, useState } from 'react'
import { Clock3, MapPin, Phone } from 'lucide-react'
import { Link } from 'react-router-dom'
import SectionHeading from '../../../components/ui/SectionHeading'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import { useI18n } from '../../../lib/i18n'

interface ShopProfile {
  phoneNumber: string | null
  address: string | null
  openingHours: string
}

interface DayHours {
  opensAt: string
  closesAt: string
}

const weekdays: Array<{ key: string; label: string }> = [
  { key: 'SUNDAY', label: 'الأحد' },
  { key: 'MONDAY', label: 'الاثنين' },
  { key: 'TUESDAY', label: 'الثلاثاء' },
  { key: 'WEDNESDAY', label: 'الأربعاء' },
  { key: 'THURSDAY', label: 'الخميس' },
  { key: 'FRIDAY', label: 'الجمعة' },
  { key: 'SATURDAY', label: 'السبت' },
]

function formatTime(value: string, language: 'ar' | 'en') {
  const [hour, minute] = value.split(':').map(Number)
  return new Intl.DateTimeFormat(language === 'ar' ? 'ar-PS' : 'en-US', {
    hour: 'numeric', minute: '2-digit',
  }).format(new Date(2000, 0, 1, hour, minute))
}

function readOpeningHours(value: string): Record<string, DayHours | null> {
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('بيانات ساعات العمل غير صالحة.')
  }
  const schedule = parsed as Record<string, unknown>
  return Object.fromEntries(weekdays.map(({ key }) => {
    const day = schedule[key]
    if (day === null || day === undefined) return [key, null]
    if (typeof day !== 'object' || Array.isArray(day)) {
      throw new Error('تعذر قراءة ساعات العمل المحفوظة.')
    }
    const hours = day as Record<string, unknown>
    if (typeof hours.opensAt !== 'string' || typeof hours.closesAt !== 'string') {
      throw new Error('تعذر قراءة ساعات العمل المحفوظة.')
    }
    return [key, { opensAt: hours.opensAt, closesAt: hours.closesAt }]
  }))
}

function ContactSection() {
  const { t, language } = useI18n()
  const [profile, setProfile] = useState<ShopProfile | null>(null)
  const [schedule, setSchedule] = useState<Record<string, DayHours | null> | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void apiRequest<ShopProfile>('/api/shop-profile')
      .then((shop) => {
        if (!active) return
        setProfile(shop)
        setSchedule(readOpeningHours(shop.openingHours))
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل بيانات التواصل.'))
      })
    return () => { active = false }
  }, [])

  const openDays = weekdays.filter(({ key }) => schedule?.[key])

  return (
    <section className="section contact-section" id="contact">
      <div className="container contact-grid">
        <div className="contact-copy">
          <SectionHeading
            eyebrow="تواصل معنا"
            title="يسعدنا أن نراك."
            description="بيانات التواصل وساعات الزيارة المعتمدة للمحل."
          />
          <Link className="contact-cta" to="/services">
            {t('تعرّف على خدماتنا')}
            <span aria-hidden="true">←</span>
          </Link>
        </div>
        <div className="contact-details">
          {error && <p className="api-state api-state--error" role="alert">{error}</p>}
          {!profile && !error && <p className="api-state" role="status">جارٍ تحميل بيانات المحل...</p>}
          {profile && (
            <>
              <div className="contact-detail">
                <span className="contact-detail-icon" aria-hidden="true"><Phone size={18} strokeWidth={1.5} /></span>
                <div>
                  <span>{t('الهاتف')}</span>
                  {profile.phoneNumber
                    ? <a className="contact-value-link" href={`tel:${profile.phoneNumber}`} dir="ltr">{profile.phoneNumber}</a>
                    : <strong>{t('لم يُضف في إعدادات المحل.')}</strong>}
                </div>
              </div>
              <div className="contact-detail">
                <span className="contact-detail-icon" aria-hidden="true"><MapPin size={18} strokeWidth={1.5} /></span>
                <div><span>{t('العنوان')}</span>
                  <strong>{profile.address || t('لم يُضف في إعدادات المحل.')}</strong></div>
              </div>
              <div className="contact-detail contact-detail--hours">
                <span className="contact-detail-icon" aria-hidden="true"><Clock3 size={18} strokeWidth={1.5} /></span>
                <div><span>{t('ساعات العمل الأسبوعية')}</span>
                  {openDays.length > 0
                    ? <ul className="contact-hours-list">{openDays.map(({ key, label }) => {
                      const hours = schedule?.[key]
                      return hours && (
                        <li key={key}><span>{t(label)}</span>
                          <strong>{formatTime(hours.opensAt, language)} - {formatTime(hours.closesAt, language)}</strong></li>
                      )
                    })}</ul>
                    : <strong>{t('لم تُحدد ساعات العمل في إعدادات المحل.')}</strong>}
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </section>
  )
}

export default ContactSection
