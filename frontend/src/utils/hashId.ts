export function hashId(id: string): number {
    let h = 0;
    for (let i = Math.max(0, id.length - 8); i < id.length; i++)
        h = (Math.imul(h, 31) + id.charCodeAt(i)) >>> 0;
    return h;
}
