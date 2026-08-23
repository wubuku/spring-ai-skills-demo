export interface SkillApiIndexEntry {
  skillName: string;
  path: string;
  method: string;
  description: string;
}

export interface SkillApiValidationResult {
  valid: boolean;
  error?: string;
}

export function validateSkillApiUrl(
  method: string,
  url: string,
  apiIndex: Record<string, SkillApiIndexEntry>,
): SkillApiValidationResult;
