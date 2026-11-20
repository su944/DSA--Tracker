import { useEffect, useRef } from 'react'
import {
  BookOpen, CalendarDays, ChartNoAxesColumn, CodeXml, LayoutDashboard,
  ListChecks, Settings, Tags, Target,
} from 'lucide-react'
import { Link, NavLink, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { ProblemLibrary } from './catalog/ProblemLibrary'
import { ProblemForm } from './catalog/ProblemForm'
import { ProblemDetails } from './catalog/ProblemDetails'
import { TopicManagement } from './catalog/TopicManagement'
import { GoalsPage } from './planning/GoalsPage'
import { PlannerPage } from './planning/PlannerPage'
import { DashboardPage } from './reporting/DashboardPage'
import { AnalyticsPage } from './reporting/AnalyticsPanels'
import { RevisionsPage } from './revision/RevisionsPage'
import { SettingsPage } from './settings/SettingsPage'

const destinations = [
  { name: 'Dashboard', path: '/', icon: LayoutDashboard },
  { name: 'Problems', path: '/problems', icon: ListChecks },
  { name: 'Topics', path: '/topics', icon: Tags },
  { name: 'Goals', path: '/goals', icon: Target },
  { name: 'Daily Planner', path: '/planner', icon: CalendarDays },
  { name: 'Revisions', path: '/revisions', icon: BookOpen },
  { name: 'Analytics', path: '/analytics', icon: ChartNoAxesColumn },
  { name: 'Settings', path: '/settings', icon: Settings },
]

function Shell() {
  const main = useRef<HTMLElement>(null)
  const { pathname } = useLocation()

  useEffect(() => {
    window.scrollTo(0, 0)
    main.current?.focus({ preventScroll: true })
  }, [pathname])

  return (
    <div className="app-shell min-h-screen lg:grid">
      <a href="#main-content" className="skip-link" onClick={(event) => {
        event.preventDefault()
        main.current?.focus()
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
      <main ref={main} className="main-content" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}

export function App() {
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<DashboardPage />} />
        <Route path="/problems" element={<ProblemLibrary />} />
        <Route path="/problems/new" element={<ProblemForm />} />
        <Route path="/problems/:id" element={<ProblemDetails />} />
        <Route path="/problems/:id/edit" element={<ProblemForm />} />
        <Route path="/topics" element={<TopicManagement />} />
        <Route path="/goals" element={<GoalsPage />} />
        <Route path="/planner" element={<PlannerPage />} />
        <Route path="/analytics" element={<AnalyticsPage />} />
        <Route path="/revisions" element={<RevisionsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
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
