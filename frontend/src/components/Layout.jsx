import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const sections = [
  {
    label: 'Workspace',
    links: [{ to: '/', label: 'Dashboard', end: true }],
  },
  {
    label: 'Templates',
    links: [
      { to: '/templates', label: 'Templates & Fields' },
      { to: '/validation', label: 'Validation Rules' },
    ],
  },
  {
    label: 'Data Pipeline',
    links: [
      { to: '/ingestion', label: 'File Uploads' },
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

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="min-h-screen bg-white">
      <header className="fixed inset-x-0 top-0 z-20 flex h-14 items-center justify-between border-b border-line bg-white px-5">
        <div className="flex items-center gap-3">
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

      <aside className="fixed top-14 bottom-0 left-0 z-10 w-64 overflow-y-auto border-r border-line bg-sidebar px-4 py-5">
        <p className="mb-4 px-2 text-[11px] font-bold uppercase tracking-wider text-muted">
          Documentation
        </p>
        {sections.map((section) => (
          <div key={section.label} className="mb-5">
            <p className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-muted">
              {section.label}
            </p>
            {section.links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  `block rounded-md px-2 py-1.5 text-sm ${
                    isActive
                      ? 'bg-pill font-medium text-accent'
                      : 'text-ink hover:bg-neutral-200/60'
                  }`
                }
              >
                {link.label}
              </NavLink>
            ))}
          </div>
        ))}
      </aside>

      <main className="ml-64 pt-14">
        <div className="mx-auto max-w-4xl px-8 py-8">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
