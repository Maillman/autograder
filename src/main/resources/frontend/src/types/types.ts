export enum Phase {
    Phase0,
    Phase1,
    Phase3,
    Phase4,
    Phase5,
    Phase6,
    Quality
}

export type TestNode = {
    testName: string,
    passed: boolean,
    ecCategory: string,
    errorMessage: string,
    children: TestNode[],
    numTestsPassed: number,
    numTestsFailed: number,
    numExtraCreditPassed: number,
    numExtraCreditFailed: number,
}

export type TestResult = {
    root: TestNode,
    extraCredit: TestNode,
    error: string
}

export type RubricItemResults = {
    notes: string,
    score: number,
    possiblePoints: number,
    testResults: TestResult,
    textResults: string,
}

export type RubricItem = {
    category: string,
    criteria: string,
    results: RubricItemResults,
}

//unfortunately with the way gson serializes maps and the way javascript parses that json each needs to be its own item
export type RubricItems = {
    PASSOFF_TESTS: RubricItem,
    UNIT_TESTS: RubricItem,
    QUALITY: RubricItem,
    GIT_COMMITS: RubricItem,
}

//TODO: Remove deprecated standalone rubric items between semesters when resetting db
export type Rubric = {
    items: RubricItems,
    passoffTests: RubricItem,
    unitTests: RubricItem,
    quality: RubricItem,
    passed: boolean,
    notes: string,
}

export type Submission = {
    netId: string,
    repoUrl: string,
    headHash: string,
    timestamp: string,
    phase: Phase,
    score: number,
    notes: string,
    rubric: Rubric,
    passed: boolean,
    admin: boolean,
    verifiedStatus: VerifiedStatus
}

export enum VerifiedStatus {
    Unapproved,
    ApprovedAutomatically,
    ApprovedManually,
    PreviouslyApproved,
}

export type User = {
    netId: string,
    firstName: string,
    lastName: string,
    repoUrl: string,
    role: 'STUDENT' | 'ADMIN'
}

export type CanvasSection = {
    id: number,
    name: string
}
