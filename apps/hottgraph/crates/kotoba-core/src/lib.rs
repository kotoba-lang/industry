// `kotoba`'s Core Library: The Cubical Foundation
//
// This crate provides the fundamental building blocks for the `kotoba` language,
// based on Cubical Type Theory. It defines the interval `I` (`toki`), paths (`en`),
// and the core operations that allow for the construction of higher-dimensional
// proofs and programs.
//
// The ultimate goal is to define HoTT concepts like Univalence (`tsunagari`)
// not as axioms, but as provable theorems emerging from these cubical primitives.

#![allow(dead_code)]

/// Represents a point in the Interval `I`.
/// The interval is the fundamental 1-dimensional space in our Cubical world.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Hash)]
pub enum Interval {
    /// The start of the interval, `i0`.
    I0,
    /// The end of the interval, `i1`.
    I1,
}

impl Interval {
    /// Reverses a point on the interval.
    /// `~i0` is `i1`, and `~i1` is `i0`.
    pub fn rev(self) -> Self {
        match self {
            Interval::I0 => Interval::I1,
            Interval::I1 => Interval::I0,
        }
    }

    /// The meet (`∧`) operation on the interval.
    /// Represents the "minimum" or intersection.
    /// `i0 ∧ i` is always `i0`.
    /// `i1 ∧ i` is `i`.
    pub fn meet(self, other: Self) -> Self {
        match (self, other) {
            (Interval::I0, _) | (_, Interval::I0) => Interval::I0,
            (Interval::I1, Interval::I1) => Interval::I1,
        }
    }

    /// The join (`∨`) operation on the interval.
    /// Represents the "maximum" or union.
    /// `i0 ∨ i` is `i`.
    /// `i1 ∨ i` is always `i1`.
    pub fn join(self, other: Self) -> Self {
        match (self, other) {
            (Interval::I1, _) | (_, Interval::I1) => Interval::I1,
            (Interval::I0, Interval::I0) => Interval::I0,
        }
    }
}

/// Represents a Path `en` between two points of a type `T`.
/// A path is fundamentally a function from the Interval `I` to the type `T`.
/// `path(i0)` is the starting point, and `path(i1)` is the ending point.
pub struct Path<T: 'static> {
    /// The underlying function from the interval `toki` to a type.
    f: Box<dyn Fn(Interval) -> T>,
}

impl<T> Path<T> {
    /// Creates a new path `en`.
    pub fn new(f: impl Fn(Interval) -> T + 'static) -> Self {
        Self { f: Box::new(f) }
    }

    /// Evaluates the path at a given point in the interval `toki`.
    pub fn at(&self, i: Interval) -> T {
        (self.f)(i)
    }

    /// Gets the starting point of the path (`i0`).
    pub fn start(&self) -> T {
        self.at(Interval::I0)
    }

    /// Gets the ending point of the path (`i1`).
    pub fn end(&self) -> T {
        self.at(Interval::I1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_interval_ops() {
        assert_eq!(Interval::I0.rev(), Interval::I1);
        assert_eq!(Interval::I1.rev(), Interval::I0);
        assert_eq!(Interval::I0.meet(Interval::I1), Interval::I0);
        assert_eq!(Interval::I1.meet(Interval::I1), Interval::I1);
        assert_eq!(Interval::I0.join(Interval::I1), Interval::I1);
        assert_eq!(Interval::I0.join(Interval::I0), Interval::I0);
    }

    #[test]
    fn test_path_constant() {
        // A constant path from 42 to 42.
        let p = Path::new(|_| 42);
        assert_eq!(p.start(), 42);
        assert_eq!(p.end(), 42);
    }

    #[test]
    fn test_path_linear() {
        // A "linear" path from 0 to 100.
        let p = Path::new(|i| match i {
            Interval::I0 => 0,
            Interval::I1 => 100,
        });
        assert_eq!(p.start(), 0);
        assert_eq!(p.end(), 100);
    }
}
