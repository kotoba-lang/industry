// Hexagonal Architecture + CQRS - Spirit in Physics
// 再エクスポート順: 00→80 の順で固定

// 00_schema - zod等の型・定数（無依存）
export * from './00_schema';

// 10_events - CMD_*/EV_* 列挙（有限語彙）
export * from './10_events';

// 20_ports - 抽象Port（ドメインが依存するだけ）
export * from './20_ports';

// 30_fold - 純関数（MDAG -> 投影）※副作用禁止
export * from './30_fold';

// 40_domain - xstate machines（UI非依存）
export * from './40_domain';

// 50_adapters - RouteHandler/ServerActions/外部API実装
export * from './50_adapters';

// 60_projection - selectors/ViewModel（foldの薄ラッパ）
export * from './60_projection';

// 70_supervisors - ルート単位の調停（invalidate/revalidate）
export * from './70_supervisors';

// 80_app - app/(segments)/...（RSC & Client）
// Note: 80_appは直接importせず、Next.jsのルーティングを使用
