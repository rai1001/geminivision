// Stub para Fase 1 — se implementará en Fase 4 con Supabase/CulinaryOS

export interface FunctionCall {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

export interface FunctionResponse {
  id: string;
  response: Record<string, unknown>;
}

/**
 * Maneja function calls de Gemini.
 * Fase 1: retorna error no implementado.
 * Fase 4: conectará con Supabase CulinaryOS.
 */
export async function handleFunctionCall(call: FunctionCall): Promise<FunctionResponse> {
  console.warn(`[functionCalling] Tool call recibido pero no implementado: ${call.name}`);

  return {
    id: call.id,
    response: {
      error: 'Function calling no implementado en Fase 1',
      availableInPhase: 4,
    },
  };
}
