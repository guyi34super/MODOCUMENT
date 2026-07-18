import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

// Nav mirrors the product journey: Home, then the numbered document flow,
// then the extraction toolset and account.
const sections = [
  {
    links: [{ to: '/', label: 'Home', end: true }],
  },
  {
    label: 'Document Flow',
    links: [
      { to: '/templates', label: 'Design Templates', step: 1 },
      { to: '/validation', label: 'Set Field Rules', step: 2 },
      { to: '/ingestion', label: 'Upload & Process', step: 3 },
    ],
  },
  {
    label: 'Data Extraction',
    links: [
      { to: '/extraction/sources', label: 'Extraction Sources' },
      { to: '/extraction/jobs', label: 'Extraction Jobs' },
    ],
  },
  {
    label: 'Account',
    links: [{ to: '/profile', label: 'Profile' }],
  },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(true);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="min-h-screen bg-white">
      <header className="fixed inset-x-0 top-0 z-20 flex h-14 items-center justify-between border-b border-line bg-white px-5">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setSidebarOpen((open) => !open)}
            aria-label="Toggle sidebar"
            aria-expanded={sidebarOpen}
            className="cursor-pointer rounded p-2 text-ink hover:bg-neutral-200/60"
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 18 18"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M2 4.5h14M2 9h14M2 13.5h14" />
            </svg>
          </button>
          <div className="flex size-9 items-center justify-center rounded border-2 border-ink text-base font-black">
            MO
          </div>
          <div className="leading-tight">
            <p className="text-sm font-bold tracking-wide">MoDocument</p>
            <p className="text-xs text-muted">
              <span className="font-semibold text-ink">intelligence</span> that works
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <span className="hidden text-muted sm:block">{user?.displayName || user?.email}</span>
          <button
            onClick={handleLogout}
            className="cursor-pointer rounded border border-line px-3 py-1 text-accent hover:bg-pill/50"
          >
            Log out
          </button>
        </div>
      </header>

      <aside
        className={`fixed top-14 bottom-0 left-0 z-10 w-64 overflow-y-auto border-r border-line bg-sidebar px-4 py-5 transition-transform duration-200 ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {sections.map((section, i) => (
          <div key={section.label || i} className="mb-5">
            {section.label && (
              <p className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-muted">
                {section.label}
              </p>
            )}
            {section.links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  `flex items-center gap-2 rounded-md px-2 py-1.5 text-sm ${
                    isActive
                      ? 'bg-pill font-medium text-accent'
                      : 'text-ink hover:bg-neutral-200/60'
                  }`
                }
              >
                {({ isActive }) => (
                  <>
                    {link.step && (
                      <span
                        className={`flex size-5 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ${
                          isActive ? 'bg-accent text-white' : 'bg-neutral-200/80 text-muted'
                        }`}
                      >
                        {link.step}
                      </span>
                    )}
                    {link.label}
                  </>
                )}
              </NavLink>
            ))}
          </div>
        ))}
      </aside>

      <main className={`pt-14 transition-[margin] duration-200 ${sidebarOpen ? 'ml-64' : 'ml-0'}`}>
        <div className="mx-auto max-w-4xl px-8 py-8">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
