import { Link } from 'react-router-dom';

/** Docs-style page heading: breadcrumbs, big light title, muted subtitle. */
export default function PageHeader({ crumbs = [], title, subtitle, actions }) {
  return (
    <div className="mb-6">
      {crumbs.length > 0 && (
        <nav className="mb-3 text-sm">
          {crumbs.map((crumb, i) => (
            <span key={i}>
              {i > 0 && <span className="mx-1.5 text-muted">›</span>}
              {crumb.to ? (
                <Link to={crumb.to} className="text-accent hover:underline">
                  {crumb.label}
                </Link>
              ) : (
                <span className="text-muted">{crumb.label}</span>
              )}
            </span>
          ))}
        </nav>
      )}
      <div className="flex items-end justify-between gap-4 border-b border-line pb-4">
        <div>
          <h1 className="text-3xl font-normal text-ink">{title}</h1>
          {subtitle && <p className="mt-1 text-base text-muted">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 gap-2">{actions}</div>}
      </div>
    </div>
  );
}
