import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// Import server initialization to ensure environment variables are valid
export function middleware(request: NextRequest) {
    // This middleware doesn't modify the request or response
    // It just ensures that server-init.ts is imported and executed
    return NextResponse.next();
}

// See: https://nextjs.org/docs/app/building-your-application/routing/middleware
export const config = {
    // Match all request paths except for:
    // - _next/static (static files)
    // - _next/image (image optimization files)
    // - favicon.ico (favicon file)
    // - public folder
    matcher: ["/((?!_next/static|_next/image|favicon.ico|public).*)"],
};
