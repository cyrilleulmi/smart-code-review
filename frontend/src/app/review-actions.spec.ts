import { afterEach, describe, expect, it } from 'vitest';
import { setLang } from './i18n';
import { approvalText } from './review-actions';

describe('approvalText', () => {
  afterEach(() => setLang('de'));

  it('says that nobody has approved yet', () => {
    expect(approvalText({ approvedBy: [], approvalsLeft: 0 })).toBe('Noch nicht genehmigt');
  });

  it('names who approved', () => {
    expect(approvalText({ approvedBy: ['anna', 'ben'], approvalsLeft: 0 })).toBe('Genehmigt von anna, ben');
  });

  it('adds how many approvals are still missing', () => {
    expect(approvalText({ approvedBy: ['anna'], approvalsLeft: 1 })).toBe('Genehmigt von anna · 1 Genehmigung fehlt');
    expect(approvalText({ approvedBy: [], approvalsLeft: 2 })).toBe('Noch nicht genehmigt · 2 Genehmigungen fehlen');
  });

  it('speaks English', () => {
    setLang('en');

    expect(approvalText({ approvedBy: ['anna'], approvalsLeft: 2 })).toBe('Approved by anna · Requires 2 approvals');
  });
});
