import type { Metadata, Viewport } from 'next'
import './globals.css'
import { Inter } from 'next/font/google'
import { ThemeProvider } from '@/components/theme-provider'
import { Toaster } from '@/components/ui/sonner'

const inter = Inter({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-inter',
})

export const metadata: Metadata = {
  title: {
    default: 'Prehender - Stream Management',
    template: '%s | Prehender'
  },
  description: 'A powerful stream management and note-taking application with real-time collaboration and advanced visualization.',
  keywords: ['stream management', 'note-taking', 'knowledge management', 'productivity', 'collaboration'],
  authors: [{ name: 'Jun Kawasaki' }],
  creator: 'Jun Kawasaki',
  publisher: 'Prehender',
  formatDetection: {
    email: false,
    address: false,
    telephone: false,
  },
  metadataBase: new URL('http://localhost:3000'),
  alternates: {
    canonical: '/',
  },
  openGraph: {
    type: 'website',
    locale: 'en_US',
    url: 'http://localhost:3000',
    title: 'Prehender - Stream Management',
    description: 'A powerful stream management and note-taking application with real-time collaboration and advanced visualization.',
    siteName: 'Prehender',
    images: [
      {
        url: '/placeholder.svg',
        width: 1200,
        height: 630,
        alt: 'Prehender - Stream Management',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Prehender - Stream Management',
    description: 'A powerful stream management and note-taking application with real-time collaboration and advanced visualization.',
    images: ['/placeholder.svg'],
    creator: '@prehender',
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      'max-video-preview': -1,
      'max-image-preview': 'large',
      'max-snippet': -1,
    },
  },
  manifest: '/manifest.json',
  icons: {
    icon: '/favicon.ico',
    shortcut: '/favicon-16x16.png',
    apple: '/apple-touch-icon.png',
  },
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
    { media: '(prefers-color-scheme: dark)', color: '#0f172a' },
  ],
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `
              try {
                if (typeof window !== 'undefined' && window.localStorage) {
                  const theme = localStorage.getItem('theme') || 'light'
                  document.documentElement.classList.toggle('dark', theme === 'dark')
                }
              } catch (e) {}
            `,
          }}
        />
      </head>
      <body className={`${inter.className} antialiased`}>
        <ThemeProvider
          attribute="class"
          defaultTheme="light"
          enableSystem
          disableTransitionOnChange
        >
          <div className="relative flex min-h-screen flex-col bg-background">
            <div className="flex-1">
              {children}
            </div>
          </div>
          <Toaster
            position="bottom-right"
            toastOptions={{
              duration: 4000,
              className: 'font-medium',
            }}
          />
        </ThemeProvider>
      </body>
    </html>
  )
}
