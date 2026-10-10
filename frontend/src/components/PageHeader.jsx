import { Link } from 'react-router-dom';

/**
 * Docs-style page heading: breadcrumbs, compact title with inline subtitle,
 * right-aligned actions. `sticky` pins the header under the fixed app bar so
 * the page's primary actions stay reachable while scrolling.
 */
export default function PageHeader({ crumbs = [], title, subtitle, actions, sticky = false }) {
  // A single self-referential crumb adds nothing — only render the trail when
  // it navigates somewhere or has depth.
  const showCrumbs = crumbs.length > 1 || crumbs.some((c) => c.to);
  return (
    <div
      className={`mb-4 border-b border-line bg-white pb-3 ${
        sticky
          ? 'sticky top-14 z-10 -mx-4 -mt-6 px-4 pt-4 sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8'
          : ''
      }`}
    >
      {showCrumbs && (
        <nav aria-label="Breadcrumb" className="mb-0.5 text-xs">
          {crumbs.map((crumb, i) => (
            <span key={i}>
              {i > 0 && (
                <span aria-hidden="true" className="mx-1.5 text-muted">
                  ›
                </span>
              )}
              {crumb.to ? (
                <Link to={crumb.to} className="text-accent hover:underline">
                  {crumb.label}
                </Link>
              ) : (
                <span
                  className="text-muted"
                  aria-current={i === crumbs.length - 1 ? 'page' : undefined}
                >
                  {crumb.label}
                </span>
              )}
            </span>
          ))}
        </nav>
      )}
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 items-baseline gap-3">
          <h1 className="truncate text-xl font-normal text-ink">{title}</h1>
          {subtitle && (
            <p className="hidden min-w-0 truncate text-sm text-muted md:block">{subtitle}</p>
          )}
        </div>
        {actions && <div className="flex shrink-0 gap-2">{actions}</div>}
      </div>
    </div>
  );
}
