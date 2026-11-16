export function decisionForQuery(query: string): { question: string; choices: { label: string; query: string }[] } | undefined {
  const text = query.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ')
  if (/\b(?:job|work|employment)\b/.test(text) && !/\b(?:employee|employer|contractor|freelance|self employed)\b/.test(text)) return {
    question: 'What kind of work are you preparing for?',
    choices: [
      { label: 'Starting as an employee', query: 'new employee onboarding' },
      { label: 'Working as an independent contractor', query: 'independent contractor' },
      { label: 'Handling payroll as an employer', query: 'employer payroll' },
    ],
  }
  if (/\b(?:health insurance|health care|healthcare)\b/.test(text) && !/\b(?:medicare|marketplace)\b/.test(text)) return {
    question: 'Which coverage are you looking for?',
    choices: [
      { label: 'Medicare enrollment', query: 'Medicare enrollment' },
      { label: 'Health Insurance Marketplace', query: 'health insurance marketplace' },
    ],
  }
  return undefined
}
