import type { Issue } from "./types";
const base = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api/v1";
export class ApiError extends Error {
  constructor(message: string, public issues: Issue[] = [], public hint = "") { super(message); }
}
export async function request(path: string, body?: unknown, file = false, reviewed = false): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(base + path, {
      method: body === undefined ? "GET" : "POST", headers: body === undefined || file ? undefined : { "Content-Type": "application/json", "X-TableFlow-Reviewed": String(reviewed) },
      body: file ? body as FormData : JSON.stringify(body), signal: AbortSignal.timeout(60000),
    });
  } catch { throw new ApiError("API에 연결할 수 없습니다. 백엔드 실행 상태를 확인한 뒤 다시 시도해주세요."); }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(error.message || `요청에 실패했습니다 (${response.status}).`, error.issues || [], error.hint || "");
  }
  return response;
}
/** DELETE 요청(대용량 이전 취소 등). 오류 응답은 request와 같은 ApiError로 변환한다. */
export async function remove(path: string): Promise<Response> {
  let response: Response;
  try { response = await fetch(base + path, {method: "DELETE", signal: AbortSignal.timeout(60000)}); }
  catch { throw new ApiError("API에 연결할 수 없습니다. 백엔드 실행 상태를 확인한 뒤 다시 시도해주세요."); }
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new ApiError(error.message || `요청에 실패했습니다 (${response.status}).`, [], ""); }
  return response;
}
