export interface NavigationItem {
  label: string
  href: string
}

export interface Service {
  name: string
  description: string
  duration?: string
  samplePrice: string
  number: string
}

export interface BookingMode {
  value: 'EXACT_APPOINTMENT' | 'CONFIRMATION_REQUEST' | 'DAY_QUEUE' | 'SAME_DAY_QUEUE'
  title: string
  description: string
  icon: 'calendar' | 'barber' | 'queue' | 'today'
}

export const site = {
  brand: 'BARBER HOUSE',
  image:
    'https://images.unsplash.com/photo-1503951914875-452162b0f3f1?auto=format&fit=crop&w=1400&q=85',
}

export const navigation: NavigationItem[] = [
  { label: 'الرئيسية', href: '/' },
  { label: 'الخدمات', href: '/services' },
  { label: 'طرق الحجز', href: '/booking' },
  { label: 'عن المحل', href: '/about' },
  { label: 'تواصل معنا', href: '/contact' },
]

export const services: Service[] = [
  {
    name: 'حلاقة شعر',
    description: 'قصة متقنة بتفاصيل تناسب أسلوبك وإطلالتك.',
    duration: '٤٥ دقيقة',
    samplePrice: '١٢٠',
    number: '01',
  },
  {
    name: 'تحديد اللحية',
    description: 'عناية دقيقة تمنح لحيتك مظهراً مرتباً ومتوازناً.',
    duration: '٣٠ دقيقة',
    samplePrice: '٧٠',
    number: '02',
  },
  {
    name: 'شعر ولحية',
    description: 'تنسيق متكامل لإطلالة منسجمة من كل زاوية.',
    duration: '٦٠ دقيقة',
    samplePrice: '١٧٠',
    number: '03',
  },
  {
    name: 'عناية متكاملة',
    description: 'وقت مخصص للعناية بالتفاصيل وتجديد إحساسك بالانتعاش.',
    duration: '٧٥ دقيقة',
    samplePrice: '٢٢٠',
    number: '04',
  },
]

export const bookingModes: BookingMode[] = [
  {
    value: 'EXACT_APPOINTMENT',
    title: 'حجز موعد بوقت محدد',
    description: 'اختر اليوم والوقت المناسبين من المواعيد المتاحة.',
    icon: 'calendar',
  },
  {
    value: 'CONFIRMATION_REQUEST',
    title: 'الحلاق يختار الموعد',
    description: 'أرسل طلبك، وسيقترح الحلاق وقتاً مناسباً لتأكيده.',
    icon: 'barber',
  },
  {
    value: 'DAY_QUEUE',
    title: 'حجز دور ليوم محدد',
    description: 'اختر يوماً للانضمام إلى قائمته، ويستطيع الحلاق تنظيم الأدوار يدوياً.',
    icon: 'queue',
  },
  {
    value: 'SAME_DAY_QUEUE',
    title: 'دور بنفس اليوم',
    description: 'انضم إلى قائمة الانتظار للزيارات المتاحة في اليوم نفسه.',
    icon: 'today',
  },
]
