use serde::Serialize;

#[derive(Default, Serialize)]
pub struct Admission {
    next_id: u64,
    active: Option<Lease>,
}
#[derive(Serialize)]
struct Lease {
    owner: u64,
    id: u64,
    cancelled: bool,
    next_sequence: u64,
}
impl Admission {
    pub fn acquire(&mut self, owner: u64) -> Option<u64> {
        if owner == 0 || self.active.is_some() {
            return None;
        }
        self.next_id = self.next_id.checked_add(1)?;
        self.active = Some(Lease {
            owner,
            id: self.next_id,
            cancelled: false,
            next_sequence: 0,
        });
        Some(self.next_id)
    }
    pub fn accept(&mut self, owner: u64, id: u64, sequence: u64) -> bool {
        let Some(lease) = self.active.as_mut() else {
            return false;
        };
        if lease.owner != owner
            || lease.id != id
            || lease.cancelled
            || sequence != lease.next_sequence
        {
            return false;
        }
        let Some(next) = sequence.checked_add(1) else {
            return false;
        };
        lease.next_sequence = next;
        true
    }
    pub fn cancel(&mut self, owner: u64, id: u64) -> bool {
        if let Some(lease) = self
            .active
            .as_mut()
            .filter(|l| l.owner == owner && l.id == id)
        {
            lease.cancelled = true;
            true
        } else {
            false
        }
    }
    pub fn finish(&mut self, owner: u64, id: u64) -> bool {
        if self
            .active
            .as_ref()
            .is_some_and(|l| l.owner == owner && l.id == id)
        {
            self.active = None;
            true
        } else {
            false
        }
    }
    pub fn busy(&self) -> bool {
        self.active.is_some()
    }
}
