import {
  Activity, BookOpen, CalendarDays, ChartNoAxesColumn, CodeXml, LayoutDashboard,
  ListChecks, RefreshCw, Tags, Target,
} from 'lucide-react'
import { Link, NavLink, Outlet, Route, Routes } from 'react-router-dom'
import { useBackendHealth, type BackendHealth } from './useBackendHealth'
import { ProblemLibrary } from './catalog/ProblemLibrary'
import { ProblemForm } from './catalog/ProblemForm'
import { ProblemDetails } from './catalog/ProblemDetails'
import { TopicManagement } from './catalog/TopicManagement'

const featurePages = [
  { name: 'Goals', path: '/goals', icon: Target, description: 'Set daily, weekly, and topic-based practice targets.' },
  { name: 'Daily Planner', path: '/planner', icon: CalendarDays, description: 'Schedule problems for a practice date.' },
  { name: 'Revisions', path: '/revisions', icon: BookOpen, description: 'See upcoming reviews and revisit solved problems.' },
  { name: 'Analytics', path: '/analytics', icon: ChartNoAxesColumn, description: 'Review solve trends and breakdowns by difficulty and topic.' },
] as const

const destinations = [
  { name: 'Dashboard', path: '/', icon: LayoutDashboard },
  { name: 'Problems', path: '/problems', icon: ListChecks },
  { name: 'Topics', path: '/topics', icon: Tags },
  ...featurePages,
]

const connection = {
  checking: { label: 'Checking backend', detail: 'Checking the local API and database.' },
  online: { label: 'Backend connected', detail: 'The local API and SQLite are responding.' },
  unavailable: { label: 'Database unavailable', detail: 'The local API responded, but its SQLite check failed.' },
  offline: { label: 'Backend unreachable', detail: 'Start the Spring Boot backend, then check the connection again.' },
} satisfies Record<BackendHealth, { label: string; detail: string }>

function Shell() {
  return (
    <div className="app-shell min-h-screen lg:grid">
      <a href="#main-content" className="skip-link" onClick={(event) => {
        event.preventDefault()
        document.getElementById('main-content')?.focus()
      }}>Skip to content</a>
      <aside className="sidebar">
        <Link to="/" className="brand">
          <span className="brand-mark"><CodeXml size={23} aria-hidden="true" /></span>
          <span>DSA Tracker</span>
        </Link>
        <nav className="navigation" aria-label="Main navigation">
          {destinations.map(({ name, path, icon: Icon }) => (
            <NavLink key={path} to={path} end={path === '/'} className="nav-link">
              <Icon size={19} aria-hidden="true" />
              <span>{name}</span>
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="main-content" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}

function Dashboard() {
  const { health, refresh } = useBackendHealth()
  const { label, detail } = connection[health]

  return (
    <>
      <h1>Dashboard</h1>
      <section className="connection-card" aria-labelledby="connection-heading">
        <div className="connection-icon"><Activity size={23} aria-hidden="true" /></div>
        <div className="connection-copy">
          <h2 id="connection-heading">Local connection</h2>
          <div role="status" className="connection-status">
            <p className="status-label"><span className={`status-dot ${health}`} aria-hidden="true" />{label}</p>
            <p className="body-text">{detail}</p>
          </div>
        </div>
        <button type="button" className="check-button" onClick={refresh} disabled={health === 'checking'}>
          <RefreshCw size={16} aria-hidden="true" />
          Check again
        </button>
      </section>
    </>
  )
}

function Placeholder({ name, description }: { name: string; description: string }) {
  return (
    <>
      <h1>{name}</h1>
      <div className="placeholder">
        <p>{description}</p>
        <p className="body-text">Coming in a later milestone.</p>
      </div>
    </>
  )
}

export function App() {
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<Dashboard />} />
        <Route path="/problems" element={<ProblemLibrary />} />
        <Route path="/problems/new" element={<ProblemForm />} />
        <Route path="/problems/:id" element={<ProblemDetails />} />
        <Route path="/problems/:id/edit" element={<ProblemForm />} />
        <Route path="/topics" element={<TopicManagement />} />
        {featurePages.map((destination) => (
          <Route key={destination.path} path={destination.path} element={<Placeholder {...destination} />} />
        ))}
        <Route path="*" element={
          <>
            <h1>Page unavailable</h1>
            <Link className="return-link" to="/">Return to Dashboard</Link>
          </>
        } />
      </Route>
    </Routes>
  )
}
